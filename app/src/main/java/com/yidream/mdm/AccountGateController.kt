package com.yidream.mdm

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The account screen is the only view inflated until the server confirms access. */
internal class AccountGateController(private val activity: AppCompatActivity,
    private val client: AppAccountClient, private val authenticated: (AppAccess) -> Unit) {
    private var codeMode = false
    private var emailCodeSent = false
    private var task: Job? = null
    private fun <T : View> view(id: Int): T = activity.findViewById(id)
    private val email get() = view<EditText>(R.id.accountEmail)
    private val phone get() = view<EditText>(R.id.accountPhone)
    private val code get() = view<EditText>(R.id.accountCode)

    fun showLogin(message: Int? = null) {
        task?.cancel()
        activity.setContentView(R.layout.activity_account)
        view<Button>(R.id.accountWhatsApp).setOnClickListener { openWhatsApp(activity) }
        view<Button>(R.id.accountEmailMode).setOnClickListener { setMode(false) }
        view<Button>(R.id.accountCodeMode).setOnClickListener { setMode(true) }
        view<Button>(R.id.accountContinue).setOnClickListener { continueLogin() }
        view<Button>(R.id.accountResend).setOnClickListener { sendCode() }
        codeMode = false; emailCodeSent = false
        setMode(false)
        message?.let { showMessage(activity.getString(it), true) }
    }

    fun checkSession() {
        task?.cancel()
        activity.setContentView(R.layout.activity_account)
        view<View>(R.id.accountForm).visibility = View.GONE
        view<View>(R.id.accountChecking).visibility = View.VISIBLE
        view<Button>(R.id.accountWhatsApp).setOnClickListener { openWhatsApp(activity) }
        view<Button>(R.id.accountRetry).setOnClickListener { checkSession() }
        view<Button>(R.id.accountOther).setOnClickListener { signOut() }
        task = activity.lifecycleScope.launch {
            try { authenticated(client.validateSession()) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!client.hasSession) showLogin(R.string.account_session_expired)
                else {
                    view<View>(R.id.accountProgress).visibility = View.GONE
                    view<TextView>(R.id.accountCheckMessage).setText(R.string.account_check_failed)
                    view<View>(R.id.accountRetry).visibility = View.VISIBLE
                    view<View>(R.id.accountOther).visibility = View.VISIBLE
                }
            }
        }
    }

    fun cancel() { task?.cancel() }

    private fun setMode(administrator: Boolean) {
        if (task?.isActive == true) return
        codeMode = administrator; emailCodeSent = false; code.setText("")
        email.isEnabled = true; phone.isEnabled = true
        view<Button>(R.id.accountEmailMode).isSelected = !administrator
        view<Button>(R.id.accountCodeMode).isSelected = administrator
        view<Button>(R.id.accountEmailMode).setTextColor(activity.getColor(if (administrator) R.color.text_secondary else R.color.accent_blue))
        view<Button>(R.id.accountCodeMode).setTextColor(activity.getColor(if (administrator) R.color.accent_blue else R.color.text_secondary))
        view<View>(R.id.accountCodeGroup).visibility = if (administrator) View.VISIBLE else View.GONE
        view<TextView>(R.id.accountCodeLabel).setText(if (administrator) R.string.account_admin_code else R.string.account_received_code)
        code.inputType = if (administrator) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_CLASS_NUMBER
        view<Button>(R.id.accountContinue).setText(if (administrator) R.string.account_activate else R.string.account_send_code)
        view<View>(R.id.accountResend).visibility = View.GONE
        showMessage(if (administrator) activity.getString(R.string.account_admin_note) else "", false)
    }

    private fun details(): Pair<String, String>? {
        email.error = null; phone.error = null; code.error = null
        val address = try { AccountInput.email(email.text.toString()) }
        catch (_: IllegalArgumentException) { email.error = activity.getString(R.string.account_invalid_email); email.requestFocus(); return null }
        val number = try { AccountInput.phone(phone.text.toString()) }
        catch (_: IllegalArgumentException) { phone.error = activity.getString(R.string.account_invalid_phone); phone.requestFocus(); return null }
        return address to number
    }

    private fun continueLogin() {
        val (address, number) = details() ?: return
        if (!codeMode && !emailCodeSent) { sendCode(); return }
        try { if (codeMode) AccountInput.administratorCode(code.text.toString()) else AccountInput.emailCode(code.text.toString()) }
        catch (_: IllegalArgumentException) { code.error = activity.getString(if (codeMode) R.string.account_invalid_admin_code else R.string.account_invalid_email_code); return }
        busy(true)
        task = activity.lifecycleScope.launch {
            try {
                val access = if (codeMode) client.activateCode(address, number, code.text.toString())
                    else client.verifyEmailCode(address, number, code.text.toString())
                code.setText("")
                authenticated(access)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showMessage(errorMessage(error), true); busy(false) }
            finally { if (activity.findViewById<View>(R.id.accountContinue) != null) busy(false) }
        }
    }

    private fun sendCode() {
        val (address, _) = details() ?: return
        busy(true)
        task = activity.lifecycleScope.launch {
            try {
                client.requestEmailCode(address)
                emailCodeSent = true
                email.isEnabled = false; phone.isEnabled = false
                view<View>(R.id.accountCodeGroup).visibility = View.VISIBLE
                view<View>(R.id.accountResend).visibility = View.VISIBLE
                view<Button>(R.id.accountContinue).setText(R.string.account_sign_in)
                showMessage(activity.getString(R.string.account_code_sent), false)
                code.requestFocus()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { showMessage(errorMessage(error), true) }
            finally { busy(false) }
        }
    }

    private fun busy(value: Boolean) {
        listOf(R.id.accountContinue, R.id.accountResend, R.id.accountEmailMode, R.id.accountCodeMode).forEach { view<View>(it).isEnabled = !value }
        if (value) showMessage(activity.getString(R.string.account_working), false)
    }

    private fun showMessage(value: String, error: Boolean) {
        view<TextView>(R.id.accountMessage).apply {
            text = value; visibility = if (value.isBlank()) View.GONE else View.VISIBLE
            setTextColor(activity.getColor(if (error) R.color.danger else R.color.text_secondary))
        }
    }

    private fun errorMessage(error: Exception): String = activity.getString(when {
        error is AccountRequestException && error.status == 429 -> R.string.account_rate_limit
        error is AccountRequestException && error.status in listOf(400, 401, 403) -> R.string.account_code_refused
        error.message?.contains("APP_ACCESS_DENIED") == true -> R.string.account_session_expired
        else -> R.string.account_network_error
    })

    fun signOut() {
        task?.cancel()
        activity.setContentView(R.layout.activity_account)
        view<View>(R.id.accountForm).visibility = View.GONE
        view<View>(R.id.accountChecking).visibility = View.VISIBLE
        view<TextView>(R.id.accountCheckMessage).setText(R.string.account_working)
        view<Button>(R.id.accountWhatsApp).setOnClickListener { openWhatsApp(activity) }
        task = activity.lifecycleScope.launch {
            try { client.logout() } catch (_: Exception) { /* Local credentials clear in the real client's finally. */ }
            showLogin()
        }
    }

    companion object {
        fun openWhatsApp(activity: AppCompatActivity) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppAccountService.WHATSAPP_URL))
            try { activity.startActivity(intent) }
            catch (_: ActivityNotFoundException) { Toast.makeText(activity, R.string.account_whatsapp_unavailable, Toast.LENGTH_LONG).show() }
        }
    }
}
