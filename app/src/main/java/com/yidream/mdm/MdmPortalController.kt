package com.yidream.mdm

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.text.InputFilter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

/** Native catalog and access requests, independent from the administrator website. */
internal class MdmPortalController(private val activity: AppCompatActivity, private val access: AppAccess? = null) {
    private val service = MdmService(activity)
    private val catalogScreen = CatalogScreenController(activity, service)
    private var sitesGeneration = 0
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
        "REQUEST_QUEUE_FULL" -> R.string.offline_queue_full
        "APP_ACCESS_DENIED", "SESSION_CHANGED" -> R.string.account_session_expired
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
    fun pauseStoreUpdates() = catalogScreen.pause()
    fun refreshSites() {
        val generation = ++sitesGeneration
        val content = activity.findViewById<LinearLayout>(R.id.sitesContent) ?: return
        fun current() = generation == sitesGeneration && activity.findViewById<LinearLayout>(R.id.sitesContent) === content
        content.removeAllViews()
        label(content, text(R.string.managed_sites_title), true)
        val online = access?.offline != true && InternetReachability.hasInternetRoute(activity)
        if (online) label(content, text(R.string.managed_sites_limits))
        if (needsPairing(content)) return
        if (online) action(content, text(R.string.managed_sites_open_approved)) { openSite() }
        action(content, text(R.string.managed_sites_new_request), true) { requestDialog() }
        action(content, text(R.string.offline_new_app_request), true) { chooseApp() }
        val state = label(content, text(if (online) R.string.mdm_loading else R.string.offline_requests_hint))
        activity.lifecycleScope.launch {
            try {
                val scope = access?.scope()
                val pending = withContext(Dispatchers.IO) {
                    if (scope != null) OfflineRequests.pending(activity, scope) else emptyList()
                }
                if (!current()) return@launch
                for (request in pending) {
                    val name = if (request.kind == QueuedDeviceRequest.Kind.APP) appLabel(request.target) else request.target
                    val status = text(if (request.failed) R.string.offline_request_failed else R.string.offline_request_waiting)
                    label(content, listOfNotNull(name, status, requestedWindow(request.requestedHours, request.requestedUntil)).joinToString("\n"))
                    if (request.failed) action(content, text(R.string.offline_discard_request), true) {
                        activity.lifecycleScope.launch {
                            withContext(Dispatchers.IO) { OfflineRequests.discard(activity, request) }
                            if (current()) refreshSites()
                        }
                    }
                }
                if (online) {
                    val requests = service.requests()
                    if (!current()) return@launch
                    content.removeView(state)
                    val policy = RemoteDevicePolicy.current(activity)
                    for (i in 0 until requests.length()) {
                        val item = requests.getJSONObject(i)
                        renderSiteRequest(item, status(item), policy)
                    }
                    val appRequests = service.appRequests()
                    if (!current()) return@launch
                    for (i in 0 until appRequests.length()) {
                        val item = appRequests.getJSONObject(i)
                        val name = appLabel(item.getString("package_name"))
                        val note = item.optString("admin_note")
                        label(content, listOf(name, text(R.string.offline_app_request), status(item), note)
                            .filter { it.isNotBlank() }.joinToString("\n"))
                    }
                    if (pending.isEmpty() && requests.length() == 0 && appRequests.length() == 0) {
                        label(content, text(R.string.mdm_empty_requests))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current()) {
                    if (state.parent === content) state.text = message(error)
                    else label(content, message(error))
                }
            }
            if (current()) action(content, text(R.string.mdm_refresh), true) { refreshSites() }
        }
    }

    private fun requestTime(item: JSONObject, field: String): Instant? =
        if (item.isNull(field)) null else runCatching { parseSiteWindowTime(item.getString(field)) }.getOrNull()

    private fun requestedWindow(hours: Int?, until: Instant?): String? = when {
        hours == 24 -> text(R.string.site_window_requested_24h)
        until != null -> activity.getString(R.string.site_window_requested_until, formatSiteWindowTime(activity, until))
        else -> null
    }

    private fun expired(item: JSONObject, now: Instant): Boolean =
        item.getString("status") == "approved" && requestTime(item, "allowed_until")?.let { it <= now } == true

    private fun status(item: JSONObject): String = text(when (item.getString("status")) {
        "approved" -> R.string.mdm_approved
        "rejected" -> R.string.mdm_rejected
        "revoked" -> R.string.mdm_revoked
        else -> R.string.mdm_pending
    })

    private fun appLabel(packageName: String): String = runCatching {
        val packages = activity.packageManager
        @Suppress("DEPRECATION")
        packages.getApplicationLabel(packages.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES))
            .toString()
    }.getOrDefault(packageName)

    private fun chooseApp() {
        val manager = DeviceOwnerManager(activity)
        val packages = activity.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
        @Suppress("DEPRECATION")
        val launchable = packages.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), flags
        ).map { it.activityInfo.packageName }.toSet()
        val candidates = launchable + manager.knownBrowsers + manager.knownAiApps + manager.knownSocial
        @Suppress("DEPRECATION")
        val apps = packages.getInstalledApplications(flags).filter { app ->
            val name = app.packageName
            app.flags and ApplicationInfo.FLAG_INSTALLED != 0 && name != activity.packageName &&
                name in candidates && DevicePolicy.validPackageName(name) && !manager.isInstallationChannel(name) &&
                manager.isAppBlocked(name)
        }.map { it.packageName to packages.getApplicationLabel(it).toString() }.sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            AlertDialog.Builder(activity).setTitle(R.string.offline_new_app_request).setMessage(R.string.offline_no_apps)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle(R.string.offline_new_app_request)
            .setItems(apps.map { it.second }.toTypedArray()) { _, position ->
                val (name, label) = apps[position]
                requestDialog(app = name, appName = label)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
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
        val until = requestTime(item, "allowed_until")
        val isExpired = expired(item, policy?.currentTime() ?: Instant.now())
        val state = if (isExpired) "expired" else item.getString("status")
        val ink = when (state) { "approved" -> 0xFF21704F.toInt(); "rejected" -> 0xFFAD4C49.toInt(); "revoked", "expired" -> 0xFF8C5A10.toInt(); else -> activity.getColor(R.color.accent_blue) }
        val fill = when (state) { "approved" -> 0xFFEAF7F0.toInt(); "rejected" -> 0xFFFFF0EF.toInt(); "revoked", "expired" -> 0xFFFFF4DF.toInt(); else -> activity.getColor(R.color.accent_blue_soft) }
        card.addView(TextView(activity).apply {
            text = if (isExpired) text(R.string.site_window_expired) else status; textSize = 11f; setTextColor(ink); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(7), dp(10), dp(7)); gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        val requested = requestedWindow(item.optInt("requested_hours").takeIf { it == 24 }, requestTime(item, "requested_until"))
        val approved = until?.let {
            activity.getString(
                if (state == "approved") R.string.site_window_allowed_until else R.string.site_window_previously_allowed_until,
                formatSiteWindowTime(activity, it)
            )
        }
        for (detail in listOfNotNull(requested, approved)) {
            card.addView(TextView(activity).apply {
                text = detail
                textSize = 13f
                setTextColor(activity.getColor(R.color.text_secondary))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
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

    private fun chooseSiteWindowEnd(previous: Instant?, onChoose: (Instant) -> Unit) {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val initial = (previous?.takeIf { it > now } ?: now.plus(Duration.ofDays(1))).atZone(zone)
        val datePicker = DatePickerDialog(activity, { _, year, month, day ->
            TimePickerDialog(activity, { _, hour, minute ->
                val local = LocalDateTime.of(year, month + 1, day, hour, minute)
                val chosen = zone.rules.getValidOffsets(local).firstOrNull()?.let { local.toInstant(it) }
                if (chosen != null && validSiteWindowEnd(chosen)) onChoose(chosen)
                else Toast.makeText(activity, R.string.site_window_invalid_date, Toast.LENGTH_LONG).show()
            }, initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(activity)).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
        datePicker.datePicker.minDate = now.toEpochMilli()
        datePicker.datePicker.maxDate = now.plus(Duration.ofDays(365)).toEpochMilli()
        datePicker.show()
    }

    private fun validSiteWindowEnd(time: Instant): Boolean {
        val now = Instant.now()
        return time > now && time <= now.plus(Duration.ofDays(365))
    }

    private fun requestDialog(app: String? = null, appName: String? = null) {
        val scope = access?.scope() ?: return
        val form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val address = EditText(activity).apply { hint = text(R.string.mdm_site_url); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setSingleLine(); styleInput(this) }
        val reason = EditText(activity).apply {
            hint = text(R.string.mdm_reason); maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(2000))
            styleInput(this)
        }
        val blockSite = CheckBox(activity).apply { setText(R.string.managed_sites_block_option) }
        var windowEnd: Instant? = null
        val duration = Spinner(activity).apply {
            id = View.generateViewId()
            val options = listOf(R.string.site_window_permanent, R.string.site_window_24h, R.string.site_window_trip)
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, options.map(::text)).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            minimumHeight = dp(48)
        }
        if (app == null) {
            form.addView(address)
            form.addView(blockSite)
            val windowControls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            form.addView(windowControls)
            windowControls.addView(TextView(activity).apply {
                setText(R.string.site_window_duration)
                labelFor = duration.id
            })
            windowControls.addView(duration)
            lateinit var selectDate: Button
            selectDate = action(windowControls, text(R.string.site_window_pick_date), true) {
                chooseSiteWindowEnd(windowEnd) {
                    windowEnd = it
                    selectDate.text = formatSiteWindowTime(activity, it)
                }
            }
            selectDate.visibility = View.GONE
            duration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectDate.visibility = if (position == 2) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {
                    selectDate.visibility = View.GONE
                }
            }
            blockSite.setOnCheckedChangeListener { _, checked ->
                windowControls.visibility = if (checked) View.GONE else View.VISIBLE
            }
        }
        form.addView(reason, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val requestId = UUID.randomUUID().toString()
        val dialog = AlertDialog.Builder(activity).setTitle(appName ?: text(R.string.managed_sites_new_request))
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton(R.string.mdm_send, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val target = try { app ?: MdmService.normalizeSite(address.text.toString()) }
                catch (_: Exception) {
                    address.error = text(R.string.mdm_invalid_site)
                    return@setOnClickListener
                }
                val mode = if (app == null && !blockSite.isChecked) duration.selectedItemPosition else 0
                val until = windowEnd.takeIf { mode == 2 }
                if (mode == 2 && (until == null || !validSiteWindowEnd(until))) {
                    Toast.makeText(activity, R.string.site_window_invalid_date, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val request = QueuedDeviceRequest(scope, requestId, target, reason.text.toString().trim(),
                    if (app == null) QueuedDeviceRequest.Kind.SITE else QueuedDeviceRequest.Kind.APP,
                    allowed = !blockSite.isChecked, requestedHours = 24.takeIf { mode == 1 }, requestedUntil = until)
                button.isEnabled = false
                activity.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { OfflineRequests.enqueue(activity, request) }
                        dialog.dismiss()
                        val view = activity.findViewById<View>(R.id.sitesContent)
                        refreshSites()
                        if (InternetReachability.hasInternetRoute(activity)) {
                            withContext(Dispatchers.IO) { OfflineRequests.flush(activity) }
                            if (activity.findViewById<View>(R.id.sitesContent) === view) refreshSites()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (dialog.isShowing) (if (app == null) address else reason).error = message(error)
                    }
                    finally { button.isEnabled = true }
                }
            }
        }
        dialog.show()
    }
}
