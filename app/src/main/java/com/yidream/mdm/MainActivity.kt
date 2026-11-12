package com.yidream.mdm

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.Closeable
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var appUpdates: AppUpdatesController
    private lateinit var settingsMenu: SettingsMenuController
    private lateinit var unlockManager: UnlockManager

    private lateinit var navigationPreferences: NavigationPreferences
    private lateinit var portal: MdmPortalController
    private lateinit var accountClient: AppAccountClient
    private lateinit var accountGate: AccountGateController
    private var mainVisible = false
    private var usbConfigurationImported = false
    private var usbProfileFailed = false
    private var connectivityObserver: Closeable? = null
    private var lastNetworkOnline: Boolean? = null
    private var offlineExpiry: Job? = null
    private var restoredTab: String? = null
    private var restoredSettingsPage: String? = null
    private var navigationBackCallback: OnBackPressedCallback? = null

    private lateinit var pages: Map<String, View>
    private lateinit var tabs: Map<String, View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceOwnerManager = DeviceOwnerManager(this)
        restoreFilterAppRole()
        unlockManager = UnlockManager(this)
        restoredTab = savedInstanceState?.getString("selectedTab")
        restoredSettingsPage = savedInstanceState?.getString("settingsPage")
        // Device Owner filtering and its configuration remain independent from account sign-in.
        importWebAdbConfigIfPresent()
        accountClient = AppAccountDependencies.factory(this)
        accountGate = AccountGateController(this, accountClient,
            canRecover = {
                deviceOwnerManager.isDeviceOwner() && deviceOwnerManager.isDebuggingBlocked() &&
                    unlockManager.hasKeyConfigured()
            }, recover = ::onRemoveFilterClicked, authenticated = ::showWorkspace)
        accountGate.showLogin()
    }

    private fun showWorkspace(access: AppAccess, justActivated: Boolean) {
        if (!access.offline && access.deviceId != null && deviceOwnerManager.isDeviceOwner() && !usbProfileFailed &&
            (justActivated || !usbConfigurationImported)
        ) {
            if (unlockManager.hasKeyConfigured() && deviceOwnerManager.finishProvisioning()) {
                usbConfigurationImported = false
            } else {
                Toast.makeText(this, R.string.lockdown_incomplete, Toast.LENGTH_LONG).show()
            }
        }
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.offlineBanner).visibility = if (access.offline) View.VISIBLE else View.GONE
        offlineExpiry?.cancel()
        offlineExpiry = access.offlineUntil?.let { expires ->
            lifecycleScope.launch {
                delay((expires - System.currentTimeMillis()).coerceAtLeast(1L))
                if (mainVisible) {
                    hideWorkspace()
                    accountGate.resume(null)
                }
            }
        }
        OfflineRequests.schedule(applicationContext)
        mainVisible = true
        selectedTab = MainNavigation.restore(restoredTab)

        pages = mapOf(
            "home" to findViewById(R.id.pageHome),
            "store" to findViewById(R.id.pageStore),
            "settings" to findViewById(R.id.pageSettings),
            "sites" to findViewById(R.id.pageSites)
        )
        tabs = mapOf(
            "home" to findViewById(R.id.tabHome),
            "store" to findViewById(R.id.tabStore),
            "settings" to findViewById(R.id.tabSettings),
            "sites" to findViewById(R.id.tabSites)
        )

        tabs.forEach { (key, view) -> view.setOnClickListener { selectTab(key) } }

        portal = MdmPortalController(this, access)
        findViewById<View>(R.id.rowPairDevice).setOnClickListener { portal.pair() }
        setupNavigationPreferences()
        setupQuickSettingsLaunch()
        setupLanguagePicker()
        AdminContactController(this, findViewById<LinearLayout>(R.id.settingsContactContent)).apply {
            bind()
            bindInformation(findViewById<LinearLayout>(R.id.informationContent))
        }
        refreshStatus()
        setupVersionAndContact()

        appUpdates = AppUpdatesController(this)
        settingsMenu = SettingsMenuController(this) {
            appUpdates.mount(findViewById<LinearLayout>(R.id.appUpdatesContent))
        }
        settingsMenu.bind(restoredSettingsPage)
        findViewById<View>(R.id.rowRemoveFilter).setOnClickListener { onRemoveFilterClicked() }

        findViewById<View>(R.id.homeOpenSites).setOnClickListener { selectTab("sites") }
        findViewById<View>(R.id.homeOpenStore).setOnClickListener { selectTab("store") }
        findViewById<View>(R.id.homeOpenSettings).setOnClickListener { selectTab("settings") }
        findViewById<TextView>(R.id.accountIdentity).apply {
            text = listOfNotNull(access.account.email, access.account.phone,
                access.deviceId?.let { getString(R.string.account_device_id, it) }).joinToString("\n")
            setTextIsSelectable(true)
        }
        listOf(R.id.accountSettingsWhatsApp, R.id.accountLogout).forEach { id ->
            findViewById<androidx.appcompat.widget.AppCompatButton>(id).apply {
                backgroundTintList = null
                supportBackgroundTintList = null
            }
        }
        findViewById<View>(R.id.accountSettingsWhatsApp).setOnClickListener { AccountGateController.openWhatsApp(this) }
        findViewById<View>(R.id.accountLogout).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.account_logout).setMessage(R.string.account_logout_note)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.account_logout) { _, _ ->
                    portal.pauseStoreUpdates()
                    mainVisible = false
                    appUpdates.cancel()
                    RemoteDevicePolicy.cancel(applicationContext)
                    accountGate.signOut()
                }.show()
        }
        selectTab(MainNavigation.restore(restoredTab))
        val currentSettings = settingsMenu
        if (!access.offline) {
            lifecycleScope.launch(Dispatchers.IO) { OfflineRequests.flush(applicationContext) }
            appUpdates.checkAvailability { available ->
                if (mainVisible && settingsMenu === currentSettings) currentSettings.setUpdateAvailable(available)
            }
            RemoteDevicePolicy.start(applicationContext)
        }
    }

    /** Remove only the former music launcher policy; keep the configured filter and owner. */
    private fun restoreFilterAppRole() {
        val preferences = getSharedPreferences("yidream_role_migration", MODE_PRIVATE)
        if (!deviceOwnerManager.isDeviceOwner() || preferences.getBoolean("filter_app_v56", false)) return
        try {
            stopLockTask()
        } catch (_: IllegalArgumentException) {
            // No lock-task session is active.
        } catch (_: SecurityException) {
            // Android already ended the old activity's lock-task session.
        }
        deviceOwnerManager.leaveDedicatedMode()
        preferences.edit().putBoolean("filter_app_v56", true).apply()
    }

    // ------------------------------------------------------------------
    // Navigation par onglets
    // ------------------------------------------------------------------

    private var selectedTab = "home"

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedTab", selectedTab)
        outState.putString("settingsPage", if (mainVisible && ::settingsMenu.isInitialized) settingsMenu.selected else restoredSettingsPage)
        super.onSaveInstanceState(outState)
    }

    private fun selectTab(requestedKey: String) {
        if (!mainVisible) return
        val key = MainNavigation.restore(requestedKey)
        if (key != selectedTab && ::settingsMenu.isInitialized) settingsMenu.open(null)
        selectedTab = key
        applyNavigationStyle()
        if (key == "store") portal.refreshStore()
        if (key == "sites") portal.refreshSites()
        pages.forEach { (k, v) -> v.visibility = if (k == key) View.VISIBLE else View.GONE }
        tabs.forEach { (k, v) ->
            v.isSelected = k == key
            v.alpha = 1.0f
            v.setBackgroundResource(R.drawable.bg_nav_item)
            if (v is LinearLayout) for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                val color = getColor(if (k == key) R.color.white else R.color.accent_navy)
                if (child is TextView) child.setTextColor(color)
                if (child is android.widget.ImageView) child.setColorFilter(color)
            }
        }
    }

    private fun setupNavigationPreferences() {
        navigationPreferences = NavigationPreferences(this)
        val options = findViewById<RadioGroup>(R.id.navigationOptions)
        options.check(if (navigationPreferences.showBottomBar) R.id.navigationTabsOption else R.id.navigationHomeOption)
        options.setOnCheckedChangeListener { _, checkedId ->
            navigationPreferences.showBottomBar = checkedId == R.id.navigationTabsOption
            applyNavigationStyle()
        }
        findViewById<View>(R.id.backToHome).setOnClickListener { selectTab("home") }
        navigationBackCallback?.remove()
        navigationBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mainVisible && selectedTab == "settings" && ::settingsMenu.isInitialized && settingsMenu.back()) {
                    return
                } else if (mainVisible && selectedTab != "home") {
                    selectTab("home")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }.also { onBackPressedDispatcher.addCallback(this, it) }
    }

    private fun applyNavigationStyle() {
        val bottomBar = navigationPreferences.showBottomBar
        findViewById<View>(R.id.navigationBar).visibility = if (bottomBar) View.VISIBLE else View.GONE
        findViewById<View>(R.id.compactNavigation).visibility =
            if (!bottomBar && selectedTab != "home") View.VISIBLE else View.GONE
    }

    private fun setupQuickSettingsLaunch() {
        val launch = QuickSettingsLaunch(this)
        val button = findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.quickSettingsLauncher)
        fun refresh() {
            button.setText(if (launch.launcherHidden) R.string.quick_settings_restore else R.string.quick_settings_hide)
        }
        button.backgroundTintList = null
        button.supportBackgroundTintList = null
        refresh()
        button.setOnClickListener {
            if (launch.launcherHidden) {
                launch.restoreLauncher()
                refresh()
            } else {
                button.isEnabled = false
                launch.requestHide(this) { hidden ->
                    button.isEnabled = true
                    refresh()
                    if (!hidden && !isDestroyed) Toast.makeText(this,
                        R.string.quick_settings_add_first, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Réglages — langue
    // ------------------------------------------------------------------

    private data class LangOption(val tag: String, val flag: String, val label: String)

    private fun setupLanguagePicker() {
        val options = listOf(
            LangOption("en", "🇬🇧🇺🇸", "English"),
            LangOption("fr", "🇫🇷", "Français"),
            LangOption("he", "🇮🇱", "עברית"),
            LangOption("yi", "📖", "יידיש")
        )

        val row = findViewById<LinearLayout>(R.id.languageRow)
        row.removeAllViews()
        val currentLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.language
            ?: resources.configuration.locales[0].language
        val density = resources.displayMetrics.density

        options.forEach { opt ->
            val item = TextView(this).apply {
                text = "${opt.flag}\n${opt.label}"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding((4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt())
                minHeight = (64 * density).toInt()
                setBackgroundResource(R.drawable.ui_language_chip)
                isSelected = opt.tag == when (currentLanguage) { "iw" -> "he"; "ji" -> "yi"; else -> currentLanguage }
                setTextColor(getColor(if (isSelected) R.color.accent_blue else R.color.text_secondary))
                setTypeface(typeface, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                contentDescription = opt.label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(opt.tag))
                }
            }
            row.addView(item)
        }
    }

    // ------------------------------------------------------------------
    // Réglages — statut + mises à jour
    // ------------------------------------------------------------------

    private fun refreshStatus() {
        // WebADB imports can apply policy before the account gate inflates any workspace views.
        if (!mainVisible) return
        val statusBanner = findViewById<TextView>(R.id.statusBanner)
        val isOwner = deviceOwnerManager.isDeviceOwner()
        val isProtected = isOwner && deviceOwnerManager.hasUpdateCompatibleLockdown()
        statusBanner.setText(when {
            isProtected -> R.string.status_owner
            isOwner -> R.string.lockdown_incomplete
            else -> R.string.status_not_owner
        })
        findViewById<TextView>(R.id.homeStatus).text = statusBanner.text
        if (isProtected) {
            statusBanner.setBackgroundResource(R.drawable.bg_status_success)
            statusBanner.setTextColor(getColor(R.color.status_success_text))
        } else {
            statusBanner.setBackgroundResource(R.drawable.bg_status_warning)
            statusBanner.setTextColor(getColor(R.color.status_warning_text))
        }
    }


    // ------------------------------------------------------------------
    // À propos — version, contact, suppression du filtre
    // ------------------------------------------------------------------

    private fun setupVersionAndContact() {
        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        findViewById<TextView>(R.id.versionLabel).text =
            "${getString(R.string.about_version_label)} : ${installed.versionName} (build $code)"
    }

    override fun onResume() {
        super.onResume()
        if (::accountGate.isInitialized) {
            mainVisible = false
            accountGate.resume(UsbActivation.take())
            lastNetworkOnline = InternetReachability.isOnline(this)
            connectivityObserver = InternetReachability.observe(this) { online ->
                val previous = lastNetworkOnline
                lastNetworkOnline = online
                if (previous != null && previous != online) {
                    hideWorkspace()
                    accountGate.resume(null)
                }
            }
        }
    }

    override fun onPause() {
        connectivityObserver?.close()
        connectivityObserver = null
        if (::accountGate.isInitialized) accountGate.pause()
        hideWorkspace()
        super.onPause()
    }

    private fun hideWorkspace() {
        offlineExpiry?.cancel()
        offlineExpiry = null
        if (::portal.isInitialized) portal.pauseStoreUpdates()
        RemoteDevicePolicy.pause()
        if (::appUpdates.isInitialized) appUpdates.cancel()
        if (mainVisible) {
            restoredTab = selectedTab
            restoredSettingsPage = if (::settingsMenu.isInitialized) settingsMenu.selected else null
            mainVisible = false
            // Mask the workspace before Android keeps a paused activity or task snapshot.
            accountGate.showLogin()
        }
    }

    private fun onRemoveFilterClicked() {
        val view = layoutInflater.inflate(R.layout.dialog_unlock_code, null)
        val boxes = listOf(
            view.findViewById<EditText>(R.id.codeBox0),
            view.findViewById<EditText>(R.id.codeBox1),
            view.findViewById<EditText>(R.id.codeBox2),
            view.findViewById<EditText>(R.id.codeBox3),
            view.findViewById<EditText>(R.id.codeBox4),
            view.findViewById<EditText>(R.id.codeBox5)
        )

        // Avance automatiquement au champ suivant après chaque caractère,
        // et revient en arrière au backspace sur un champ vide.
        boxes.forEachIndexed { index, box ->
            box.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!s.isNullOrEmpty() && index < boxes.size - 1) {
                        boxes[index + 1].requestFocus()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL && event.action == android.view.KeyEvent.ACTION_DOWN
                    && box.text.isEmpty() && index > 0
                ) {
                    boxes[index - 1].requestFocus()
                    boxes[index - 1].setText("")
                }
                false
            }
        }
        boxes.first().requestFocus()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.unlock_dialog_title))
            .setMessage(getString(R.string.unlock_dialog_message))
            .setView(view)
            .setPositiveButton(getString(R.string.about_remove_filter)) { _, _ ->
                val code = boxes.joinToString("") { it.text.toString() }
                when (unlockManager.verify(code)) {
                    UnlockManager.Verification.ACCEPTED -> removeFilterConfirmed()
                    UnlockManager.Verification.INCORRECT ->
                        Toast.makeText(this, R.string.unlock_invalid, Toast.LENGTH_LONG).show()
                    UnlockManager.Verification.LOCKED ->
                        Toast.makeText(this, R.string.lockdown_retry_code, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeFilterConfirmed() {
        try {
            if (!deviceOwnerManager.isDeviceOwner()) return
            if (::portal.isInitialized) portal.pauseStoreUpdates()
            RemoteDevicePolicy.cancel(applicationContext)
            runCatching { stopLockTask() }
            deviceOwnerManager.leaveDedicatedMode()
            deviceOwnerManager.removeBaseRestrictions()
            deviceOwnerManager.dpm.clearDeviceOwnerApp(packageName)
            if (!mainVisible) accountGate.showLogin()
            Toast.makeText(this, getString(R.string.unlock_success), Toast.LENGTH_LONG).show()
            refreshStatus()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Erreur : ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------
    // Import de la config poussée une fois par le Web ADB
    // ------------------------------------------------------------------

    private fun importWebAdbConfigIfPresent() {
        val configFile = File(getExternalFilesDir(null), "yidream_config.json")
        if (!configFile.exists()) return
        if (deviceOwnerManager.isDebuggingBlocked()) {
            configFile.delete()
            return
        }

        try {
            usbProfileFailed = true
            val json = configFile.inputStream().buffered().use { input ->
                val bytes = ByteArray(64 * 1024 + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count in 1 until bytes.size)
                JSONObject(String(bytes, 0, count, Charsets.UTF_8))
            }
            val profile = UsbAppProfile.parse(json)
            if (profile != null) {
                val requestId = UsbAppProfile.requestId(json)
                check(deviceOwnerManager.isDeviceOwner())
                val browser = deviceOwnerManager.disallowedWebViewBrowser(profile)
                if (browser != null) {
                    Toast.makeText(this, getString(R.string.usb_profile_webview, browser), Toast.LENGTH_LONG).show()
                    return
                }
                unlockManager.storeIntermediateKey(json.getString("unlock_intermediate_key"))
                check(deviceOwnerManager.applyUsbAppProfile(profile))
                check(configFile.delete())
                writeUsbProfileReceipt(configFile, requestId)
                usbConfigurationImported = true
                usbProfileFailed = false
                refreshStatus()
                Toast.makeText(this, "Configuration importée et appliquée.", Toast.LENGTH_LONG).show()
                return
            }
            usbProfileFailed = false

            val extraPackages = mutableListOf<String>()
            json.optJSONArray("extra_packages")?.let {
                for (i in 0 until it.length()) extraPackages.add(it.getString(i))
            }

            if (deviceOwnerManager.isDeviceOwner()) {
                val key = json.optString("unlock_intermediate_key")
                if (key.isNotBlank()) unlockManager.storeIntermediateKey(key)
                deviceOwnerManager.applyCategoryBlocking(
                    blockBrowsers = json.optBoolean("block_browsers", false),
                    blockAi = json.optBoolean("block_ai", false),
                    blockSocial = json.optBoolean("block_social", false),
                    blockStore = json.optBoolean("block_store", false),
                    extraPackages = extraPackages
                )
                deviceOwnerManager.applyBaseRestrictions()
                usbConfigurationImported = true
            }

            refreshStatus()
            Toast.makeText(this, "Configuration importée et appliquée.", Toast.LENGTH_LONG).show()
            configFile.delete()
        } catch (_: Exception) {
            if (usbProfileFailed) {
                Toast.makeText(this, R.string.lockdown_incomplete, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Config invalide, ignorée.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeUsbProfileReceipt(config: File, requestId: String) {
        val receipt = File(config.parentFile, "yidream_config_applied.json")
        val pending = File(config.parentFile, "yidream_config_applied.tmp")
        try {
            pending.writeText(JSONObject().put("profile_request_id", requestId).toString())
            check(pending.renameTo(receipt))
        } finally {
            pending.delete()
        }
    }
}

