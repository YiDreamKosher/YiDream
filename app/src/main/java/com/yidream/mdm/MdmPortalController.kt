package com.yidream.mdm

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID
import org.json.JSONObject

/** Native catalog and website requests, independent from the administrator website. */
class MdmPortalController(private val activity: AppCompatActivity) {
    private val service = MdmService(activity)
    private val catalogScreen = CatalogScreenController(activity, service)
    private var sitesGeneration = 0
    private val store get() = activity.findViewById<LinearLayout>(R.id.storeContent)
    private val sites get() = activity.findViewById<LinearLayout>(R.id.sitesContent)
    private fun text(id: Int) = activity.getString(id)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun label(parent: LinearLayout, value: String, title: Boolean = false): TextView = TextView(activity).also {
        it.text = value; it.textSize = if (title) 30f else 13f
        it.setTextColor(activity.getColor(if (title) R.color.accent_navy else R.color.text_secondary))
        it.setLineSpacing(dp(3).toFloat(), 1f)
        if (title) { it.setTypeface(it.typeface, Typeface.BOLD); it.letterSpacing = -0.025f }
        it.setPadding(if (title) 0 else dp(18), if (title) 0 else dp(16), if (title) 0 else dp(18), if (title) dp(7) else dp(16))
        if (!title) it.setBackgroundResource(R.drawable.bg_quick_action)
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun action(parent: LinearLayout, title: String, secondary: Boolean = false, run: () -> Unit): Button = Button(activity).also {
        it.text = title; it.isAllCaps = false; it.minHeight = dp(52); it.textSize = 14f
        it.setTypeface(it.typeface, Typeface.BOLD)
        it.backgroundTintList = null; it.stateListAnimator = null
        it.setBackgroundResource(if (secondary) R.drawable.ui_secondary_button else R.drawable.ui_primary_button)
        it.setTextColor(activity.getColor(if (secondary) R.color.accent_blue else R.color.white))
        if (!secondary && title == text(R.string.managed_sites_new_request)) {
            it.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ui_ic_add, 0, 0, 0)
            it.compoundDrawablePadding = dp(8)
        }
        it.setPadding(dp(20), dp(12), dp(20), dp(12))
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        it.setOnClickListener { run() }
    }
    private fun styleInput(input: EditText) {
        input.setTextColor(activity.getColor(R.color.accent_navy))
        input.setHintTextColor(activity.getColor(R.color.text_secondary))
        input.textSize = 15f
        input.setBackgroundResource(R.drawable.ui_input)
        input.setPadding(dp(16), dp(16), dp(16), dp(16))
        input.minHeight = dp(54)
    }
    private fun message(error: Throwable): String = text(when (error.message) {
        "INVALID_SITE" -> R.string.mdm_invalid_site
        "SERVICE_NOT_READY" -> R.string.mdm_service_missing
        else -> R.string.mdm_network_error
    })
    private fun needsPairing(parent: LinearLayout): Boolean {
        if (service.paired) return false
        label(parent, text(R.string.mdm_pair_description))
        action(parent, text(R.string.mdm_pair)) { pair() }
        return true
    }
    fun pair() {
        val code = EditText(activity).apply {
            hint = text(R.string.mdm_pair_code)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine()
            styleInput(this)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.mdm_pair).setView(code)
            .setPositiveButton(R.string.mdm_connect, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                button.isEnabled = false
                activity.lifecycleScope.launch {
                    try {
                        service.pair(code.text.toString()); dialog.dismiss(); refreshStore(); refreshSites()
                    } catch (error: Exception) { code.error = message(error) }
                    finally { button.isEnabled = true }
                }
            }
        }
        dialog.show()
    }
    fun refreshStore() = catalogScreen.load { pair() }
    fun refreshSites() {
        val generation = ++sitesGeneration
        sites.removeAllViews()
        label(sites, text(R.string.managed_sites_title), true)
        label(sites, text(R.string.managed_sites_limits))
        if (needsPairing(sites)) return
        action(sites, text(R.string.managed_sites_open_approved)) { openSite() }
        action(sites, text(R.string.managed_sites_new_request), true) { requestDialog() }
        val state = label(sites, text(R.string.mdm_loading))
        activity.lifecycleScope.launch {
            try {
                val requests = service.requests()
                if (generation != sitesGeneration) return@launch
                sites.removeView(state)
                val policy = RemoteDevicePolicy.current(activity)
                if (requests.length() == 0) label(sites, text(R.string.mdm_empty_requests))
                for (i in 0 until requests.length()) {
                    val item = requests.getJSONObject(i)
                    val status = text(when (item.getString("status")) {
                        "approved" -> R.string.mdm_approved
                        "rejected" -> R.string.mdm_rejected
                        "revoked" -> R.string.mdm_revoked
                        else -> R.string.mdm_pending
                    })
                    renderSiteRequest(item, status, policy)
                }
            } catch (error: Exception) { state.text = message(error) }
            if (generation != sitesGeneration) return@launch
            action(sites, text(R.string.mdm_refresh), true) { refreshSites() }
        }
    }
    internal fun renderSiteRequest(item: JSONObject, status: String, policy: DevicePolicy? = null) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundResource(R.drawable.bg_quick_action)
        }
        sites.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        card.addView(TextView(activity).apply {
            text = item.getString("site_url"); textSize = 15f
            setTextColor(activity.getColor(R.color.accent_navy)); setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        val requestedAllowed = item.optBoolean("requested_allowed", true)
        card.addView(TextView(activity).apply {
            text = text(if (requestedAllowed) R.string.managed_sites_allow_request else R.string.managed_sites_block_request)
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val state = item.getString("status")
        val ink = when (state) { "approved" -> 0xFF21704F.toInt(); "rejected" -> 0xFFAD4C49.toInt(); "revoked" -> 0xFF8C5A10.toInt(); else -> activity.getColor(R.color.accent_blue) }
        val fill = when (state) { "approved" -> 0xFFEAF7F0.toInt(); "rejected" -> 0xFFFFF0EF.toInt(); "revoked" -> 0xFFFFF4DF.toInt(); else -> activity.getColor(R.color.accent_blue_soft) }
        card.addView(TextView(activity).apply {
            text = status; textSize = 11f; setTextColor(ink); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(7), dp(10), dp(7)); gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        item.optString("admin_note").takeIf { it.isNotBlank() }?.let { note ->
            card.addView(TextView(activity).apply {
                text = note; textSize = 13f; setTextColor(activity.getColor(R.color.text_secondary))
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val site = item.getString("site_url")
        if (runCatching { policy?.permits(java.net.URI(site)) == true }.getOrDefault(false)) {
            val open = action(card, text(R.string.managed_sites_open), true) { openSite(site) }
            (open.layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(12); bottomMargin = 0 }
        }
    }

    private fun openSite(address: String? = null) {
        activity.startActivity(Intent(activity, ManagedBrowserActivity::class.java).apply {
            if (address != null) putExtra("site_address", address)
        })
    }

    private fun requestDialog() {
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val address = EditText(activity).apply { hint = text(R.string.mdm_site_url); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(); styleInput(this) }
        val reason = EditText(activity).apply { hint = text(R.string.mdm_reason); maxLines = 4; styleInput(this) }
        val blockSite = CheckBox(activity).apply { setText(R.string.managed_sites_block_option) }
        form.addView(address)
        form.addView(blockSite)
        form.addView(reason, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val requestId = UUID.randomUUID().toString()
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.managed_sites_new_request).setView(form)
            .setPositiveButton(R.string.mdm_send, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                try { MdmService.normalizeSite(address.text.toString()) }
                catch (_: Exception) { address.error = text(R.string.mdm_invalid_site); return@setOnClickListener }
                button.isEnabled = false
                activity.lifecycleScope.launch {
                    try {
                        service.requestSite(address.text.toString(), reason.text.toString(), requestId,
                            allowed = !blockSite.isChecked)
                        dialog.dismiss(); refreshSites()
                    } catch (error: Exception) { address.error = message(error) }
                    finally { button.isEnabled = true }
                }
            }
        }
        dialog.show()
    }
}
