package com.yidream.mdm

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The workspace opens only after the server confirms the device's approved account. */
internal class AccountGateController(
    private val activity: AppCompatActivity,
    private val client: AppAccountClient,
    private val canRecover: () -> Boolean,
    private val recover: () -> Unit,
    private val authenticated: (AppAccess, Boolean) -> Unit
) {
    private var task: Job? = null
    private var activation: Job? = null
    private var foreground = false
    private fun <T : View> view(id: Int): T = activity.findViewById(id)
    private val code get() = view<EditText>(R.id.accountCode)

    fun showLogin(message: Int? = null, suppliedCode: String? = null) {
        task?.cancel()
        setAccountScreen()
        code.setText(suppliedCode.orEmpty())
        view<Button>(R.id.accountContinue).setOnClickListener { activate() }
        view<Button>(R.id.accountKeep).apply {
            visibility = if (client.hasSession) View.VISIBLE else View.GONE
            setOnClickListener { checkSession() }
        }
        message?.let { showMessage(activity.getString(it), true) }
    }

    fun resume(usbCode: String?) {
        foreground = true
        if (activation?.isActive == true) return
        if (usbCode != null) {
            val replacingAccount = client.hasSession
            showLogin(suppliedCode = usbCode)
            if (replacingAccount) {
                showMessage(activity.getString(R.string.account_replace_prompt), false)
            } else {
                activate()
            }
        } else if (client.hasSession) {
            checkSession()
        }
    }

    fun pause() {
        foreground = false
        task?.cancel()
    }

    private fun checkSession() {
        task?.cancel()
        setCheckingScreen()
        view<Button>(R.id.accountRetry).setOnClickListener { checkSession() }
        view<Button>(R.id.accountOther).setOnClickListener { signOut() }
        task = activity.lifecycleScope.launch {
            try {
                val access = client.validateSession()
                if (foreground) authenticated(access, false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!client.hasSession) {
                    showLogin(R.string.account_session_expired)
                } else {
                    view<View>(R.id.accountProgress).visibility = View.GONE
                    view<TextView>(R.id.accountCheckMessage).setText(
                        if (error.message == "OFFLINE_CONFIRMATION_REQUIRED") R.string.offline_confirmation_required
                        else R.string.account_check_failed
                    )
                    view<View>(R.id.accountRetry).visibility = View.VISIBLE
                    view<View>(R.id.accountOther).visibility = View.VISIBLE
                }
            }
        }
    }

    private fun activate() {
        if (activation?.isActive == true) return
        val normalized = try {
            AccountInput.administratorCode(code.text.toString())
        } catch (_: IllegalArgumentException) {
            code.error = activity.getString(R.string.account_invalid_admin_code)
            return
        }
        val submit = view<Button>(R.id.accountContinue)
        val keep = view<Button>(R.id.accountKeep)
        submit.isEnabled = false
        keep.isEnabled = false
        code.isEnabled = false
        showMessage(activity.getString(R.string.account_working), false)
        activation = activity.lifecycleScope.launch {
            try {
                val access = client.activateCode(normalized)
                code.text.clear()
                if (foreground) authenticated(access, true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showMessage(errorMessage(error), true)
            } finally {
                activity.findViewById<EditText>(R.id.accountCode)?.isEnabled = true
                submit.isEnabled = true
                keep.isEnabled = true
            }
        }
    }

    private fun setAccountScreen() {
        activity.setContentView(R.layout.activity_account)
        listOf(R.id.accountContinue, R.id.accountKeep, R.id.accountWhatsApp,
            R.id.accountRetry, R.id.accountOther, R.id.accountMaintenance).forEach { id ->
            view<AppCompatButton>(id).apply {
                backgroundTintList = null
                supportBackgroundTintList = null
            }
        }
        view<Button>(R.id.accountWhatsApp).setOnClickListener { openWhatsApp(activity) }
        view<Button>(R.id.accountMaintenance).apply {
            visibility = if (canRecover()) View.VISIBLE else View.GONE
            setOnClickListener { recover() }
        }
    }

    private fun setCheckingScreen() {
        setAccountScreen()
        view<View>(R.id.accountForm).visibility = View.GONE
        view<View>(R.id.accountChecking).visibility = View.VISIBLE
    }

    private fun showMessage(value: String, error: Boolean) {
        view<TextView>(R.id.accountMessage).apply {
            text = value
            visibility = View.VISIBLE
            setTextColor(activity.getColor(if (error) R.color.danger else R.color.text_secondary))
        }
    }

    private fun errorMessage(error: Exception): String = activity.getString(when {
        error is AccountRequestException && error.status == 429 -> R.string.account_rate_limit
        error is AccountRequestException && error.status == 404 -> R.string.account_service_pending
        error is AccountRequestException && error.status in listOf(400, 401, 403) -> R.string.account_code_refused
        else -> R.string.account_network_error
    })

    fun signOut() {
        task?.cancel()
        setCheckingScreen()
        view<TextView>(R.id.accountCheckMessage).setText(R.string.account_working)
        task = activity.lifecycleScope.launch {
            try {
                client.logout()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The real client clears local credentials even if the network is unavailable.
            }
            showLogin()
        }
    }

    companion object {
        fun openWhatsApp(activity: AppCompatActivity) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppAccountService.WHATSAPP_URL))
            try {
                activity.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.account_whatsapp_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}
