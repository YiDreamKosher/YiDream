package com.yidream.mdm

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var updateManager: UpdateManager
    private lateinit var unlockManager: UnlockManager

    private lateinit var portal: MdmPortalController
    private lateinit var accountClient: AppAccountClient
    private lateinit var accountGate: AccountGateController
    private var mainVisible = false
    private var restoredTab: String? = null

    private lateinit var pages: Map<String, View>
    private lateinit var tabs: Map<String, View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceOwnerManager = DeviceOwnerManager(this)
        restoreFilterAppRole()
        updateManager = UpdateManager(this)
        unlockManager = UnlockManager(this)
        restoredTab = savedInstanceState?.getString("selectedTab")
        // Device Owner filtering and its configuration remain independent from account sign-in.
        importWebAdbConfigIfPresent()
        accountClient = AppAccountDependencies.factory(this)
        accountGate = AccountGateController(this, accountClient) { access -> showWorkspace(access) }
        accountGate.showLogin()
    }

    private fun showWorkspace(access: AppAccess) {
        setContentView(R.layout.activity_main)
        mainVisible = true

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

        portal = MdmPortalController(this)
        findViewById<View>(R.id.rowPairDevice).setOnClickListener { portal.pair() }
        setupLanguagePicker()
        refreshStatus()
        setupVersionAndContact()

        findViewById<View>(R.id.rowUpdates).setOnClickListener { onUpdatesClicked() }
        findViewById<View>(R.id.rowRemoveFilter).setOnClickListener { onRemoveFilterClicked() }

        findViewById<View>(R.id.homeOpenSites).setOnClickListener { selectTab("sites") }
        findViewById<View>(R.id.homeOpenStore).setOnClickListener { selectTab("store") }
        findViewById<View>(R.id.homeOpenSettings).setOnClickListener { selectTab("settings") }
        findViewById<TextView>(R.id.accountIdentity).text = access.account.email + "\n" + access.account.phone
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
                    mainVisible = false
                    accountGate.signOut()
                }.show()
        }
        selectTab(MainNavigation.restore(restoredTab))
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
        super.onSaveInstanceState(outState)
    }

    private fun selectTab(requestedKey: String) {
        val key = MainNavigation.restore(requestedKey)
        selectedTab = key
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
        statusBanner.text = if (isOwner) getString(R.string.status_owner) else getString(R.string.status_not_owner)
        findViewById<TextView>(R.id.homeStatus).text = statusBanner.text
        if (isOwner) {
            statusBanner.setBackgroundResource(R.drawable.bg_status_success)
            statusBanner.setTextColor(getColor(R.color.status_success_text))
        } else {
            statusBanner.setBackgroundResource(R.drawable.bg_status_warning)
            statusBanner.setTextColor(getColor(R.color.status_warning_text))
        }
    }

    private fun onUpdatesClicked() {
        Toast.makeText(this, getString(R.string.settings_checking), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val update = updateManager.checkForUpdate()
            if (update == null) {
                Toast.makeText(this@MainActivity, getString(R.string.settings_up_to_date), Toast.LENGTH_SHORT).show()
                return@launch
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Mise à jour ${update.versionName} disponible")
                .setMessage(update.changelog.ifBlank { "Nouvelle version disponible." })
                .setPositiveButton("Installer") { _, _ ->
                    lifecycleScope.launch {
                        Toast.makeText(this@MainActivity, "Installation…", Toast.LENGTH_SHORT).show()
                        val result = updateManager.installUpdate(update)
                        when (result) {
                            is SilentInstaller.Result.Success -> {
                                setupVersionAndContact()
                                Toast.makeText(this@MainActivity, "Mise à jour confirmée par Android.", Toast.LENGTH_LONG).show()
                            }
                            is SilentInstaller.Result.Failure -> AlertDialog.Builder(this@MainActivity)
                                .setTitle("Mise à jour non installée")
                                .setMessage(result.message)
                                .setPositiveButton("Fermer", null)
                                .show()
                        }
                    }
                }
                .setNegativeButton("Plus tard", null)
                .show()
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
        if (::accountGate.isInitialized && accountClient.hasSession) {
            mainVisible = false
            accountGate.checkSession()
        }
    }

    override fun onPause() {
        if (::accountGate.isInitialized) accountGate.cancel()
        if (mainVisible) {
            restoredTab = selectedTab
            mainVisible = false
            // Mask the workspace before Android keeps a paused activity or task snapshot.
            accountGate.showLogin()
        }
        super.onPause()
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
                if (unlockManager.verify(code)) {
                    removeFilterConfirmed()
                } else {
                    Toast.makeText(this, getString(R.string.unlock_invalid), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun removeFilterConfirmed() {
        try {
            stopLockTask()
            deviceOwnerManager.leaveDedicatedMode()
            deviceOwnerManager.removeBaseRestrictions()
            deviceOwnerManager.dpm.clearDeviceOwnerApp(packageName)
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

        try {
            val json = JSONObject(configFile.readText())

            val extraPackages = mutableListOf<String>()
            json.optJSONArray("extra_packages")?.let {
                for (i in 0 until it.length()) extraPackages.add(it.getString(i))
            }

            if (deviceOwnerManager.isDeviceOwner()) {
                deviceOwnerManager.applyCategoryBlocking(
                    blockBrowsers = json.optBoolean("block_browsers", false),
                    blockAi = json.optBoolean("block_ai", false),
                    blockSocial = json.optBoolean("block_social", false),
                    blockStore = json.optBoolean("block_store", false),
                    extraPackages = extraPackages
                )
                deviceOwnerManager.applyBaseRestrictions()
            }

            json.optString("unlock_intermediate_key", null)?.let {
                if (it.isNotBlank()) unlockManager.storeIntermediateKey(it)
            }

            refreshStatus()
            Toast.makeText(this, "Configuration importée et appliquée.", Toast.LENGTH_LONG).show()
            configFile.delete()
        } catch (e: Exception) {
            Toast.makeText(this, "Config invalide, ignorée.", Toast.LENGTH_SHORT).show()
        }
    }
}

