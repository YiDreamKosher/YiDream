package com.yidream.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.webkit.WebView

class DeviceOwnerManager(private val context: Context) {

    val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    // ---------------------------------------------------------------
    // Listes d'apps par catégorie. WhatsApp/messagerie n'est PAS dans
    // cette liste : décision explicite de ne pas restreindre cette
    // catégorie (le blocage fin de fonctionnalités internes à une app
    // tierce comme WhatsApp n'est techniquement pas possible via les
    // API Android de gestion d'appareil).
    // ---------------------------------------------------------------

    val knownBrowsers = listOf(
        "com.android.chrome", "com.chrome.beta", "org.mozilla.firefox",
        "com.sec.android.app.sbrowser", "com.opera.browser", "com.opera.mini.native",
        "com.microsoft.emmx", "com.brave.browser", "com.duckduckgo.mobile.android",
        "com.UCMobile.intl", "com.kiwibrowser.browser", "com.vivaldi.browser",
        "com.android.browser"
    )

    val knownAiApps = listOf(
        "com.openai.chatgpt", "com.google.android.apps.bard", "com.google.android.apps.aichat",
        "com.microsoft.copilot", "com.anthropic.claude", "ai.perplexity.app.android",
        "com.deepseek.chat", "com.x.grok", "com.character.ai"
    )

    val knownSocial = listOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.facebook.katana",
        "com.snapchat.android", "com.twitter.android", "com.reddit.frontpage"
    )

    val knownStores = listOf("com.android.vending")

    private val installationChannelNames = setOf(
        "com.android.vending", "com.amazon.venezia", "com.sec.android.app.samsungapps",
        "com.huawei.appmarket", "com.xiaomi.market", "com.oppo.market", "com.heytap.market",
        "com.bbk.appstore", "com.vivo.appstore", "org.fdroid.fdroid", "com.aurora.store"
    )
    private val lockdownPreferences get() = context.getSharedPreferences("yidream_installation_lock", Context.MODE_PRIVATE)
    private val chromePreferences get() = context.getSharedPreferences("yidream_managed_chrome", Context.MODE_PRIVATE)
    private val users get() = context.getSystemService(UserManager::class.java)

    private fun installationChannels(): Set<String> {
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
        )
        val packages = context.packageManager
        @Suppress("DEPRECATION")
        val storefronts = intents.flatMap { packages.queryIntentActivities(it,
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES) }
            .map { it.activityInfo.packageName }
        return installationChannelNames + storefronts +
            lockdownPreferences.getStringSet("protected_stores", emptySet()).orEmpty()
    }

    internal fun isInstallationChannel(packageName: String): Boolean = packageName in installationChannels()

    internal fun isDebuggingBlocked(): Boolean =
        users?.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES) == true

    internal fun hasUpdateCompatibleLockdown(): Boolean =
        isDebuggingBlocked() && users?.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) == true &&
            users?.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS) == false

    // ---------------------------------------------------------------
    // Blocage / déblocage d'une app (masquage : invisible et inutilisable,
    // sans désinstallation réelle qui n'est pas possible pour toutes les apps).
    // ---------------------------------------------------------------

    fun setAppBlocked(packageName: String, blocked: Boolean): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            if (!blocked && isDebuggingBlocked() && isInstallationChannel(packageName)) return false
            if (dpm.isApplicationHidden(adminComponent, packageName) != blocked &&
                !dpm.setApplicationHidden(adminComponent, packageName, blocked)) return false
            dpm.isApplicationHidden(adminComponent, packageName) == blocked
        } catch (e: Exception) {
            false
        }
    }

    /** The remote rules cover installed user apps and the existing known filter categories. */
    internal fun applyRemoteAppRule(packageName: String, allowed: Boolean): Boolean {
        if (!DevicePolicy.validPackageName(packageName) || packageName == context.packageName) return false
        return try {
            val packages = context.packageManager
            @Suppress("DEPRECATION")
            val app = packages.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            if (app.flags and ApplicationInfo.FLAG_INSTALLED == 0) return false
            val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (system && packageName !in knownBrowsers && packageName !in knownAiApps &&
                packageName !in knownSocial && packageName !in knownStores) return false
            val telecom = context.getSystemService(TelecomManager::class.java)
            if (packageName == telecom?.defaultDialerPackage ||
                (Build.VERSION.SDK_INT >= 29 && packageName == telecom?.systemDialerPackage) ||
                packageName == Telephony.Sms.getDefaultSmsPackage(context)) return false
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            val launchers = packages.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            if (launchers.any { it.activityInfo.packageName == packageName }) return false
            setAppBlocked(packageName, blocked = !allowed)
        } catch (_: Exception) {
            false
        }
    }

    /** The existing per-device Chrome app permission enables the pilot on updated devices. */
    internal fun applyManagedChrome(policy: DevicePolicy, enrollmentIsCurrent: () -> Boolean): Boolean =
        synchronized(chromePolicyLock) {
            val chrome = ChromeManagedPolicy.PACKAGE_NAME
            val requested = policy.apps[chrome]
            val previouslyManaged = chromePreferences.getBoolean(CHROME_CONFIGURED, false)
            if (!enrollmentIsCurrent()) return@synchronized false
            if (requested != true) {
                return@synchronized when {
                    requested == false -> !chromeIsWebView() && applyRemoteAppRule(chrome, false)
                    previouslyManaged -> closeManagedChrome()
                    else -> true
                }
            }
            if (!isDeviceOwner() || chromeIsWebView()) return@synchronized false
            if (!ChromeManagedPolicy.canManage(context)) {
                setAppBlocked(chrome, true)
                return@synchronized false
            }
            // Restart Chrome on rule changes. Keep it hidden if Android cannot store the policy.
            if (!ChromeManagedPolicy.matches(context, this, policy) &&
                (!setAppBlocked(chrome, true) || !ChromeManagedPolicy.apply(context, this, policy))) {
                return@synchronized false
            }
            if (!chromePreferences.edit().putBoolean(CHROME_CONFIGURED, true).commit() ||
                !enrollmentIsCurrent()) {
                setAppBlocked(chrome, true)
                return@synchronized false
            }
            applyRemoteAppRule(chrome, true)
        }

    internal fun closeManagedChrome(): Boolean = synchronized(chromePolicyLock) {
        if (!chromePreferences.getBoolean(CHROME_CONFIGURED, false)) return@synchronized true
        !chromeIsWebView() && setAppBlocked(ChromeManagedPolicy.PACKAGE_NAME, true)
    }

    internal fun canOpenManagedChrome(policy: DevicePolicy): Boolean = synchronized(chromePolicyLock) {
        val chrome = ChromeManagedPolicy.PACKAGE_NAME
        policy.apps[chrome] == true && chromePreferences.getBoolean(CHROME_CONFIGURED, false) &&
            ChromeManagedPolicy.matches(context, this, policy) &&
            runCatching { !dpm.isApplicationHidden(adminComponent, chrome) }.getOrDefault(false)
    }

    private fun chromeIsWebView(): Boolean = runCatching {
        WebView.getCurrentWebViewPackage()?.packageName == ChromeManagedPolicy.PACKAGE_NAME
    }.getOrDefault(false)

    fun isAppBlocked(packageName: String): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            dpm.isApplicationHidden(adminComponent, packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun applyCategoryBlocking(
        blockBrowsers: Boolean,
        blockAi: Boolean,
        blockSocial: Boolean,
        blockStore: Boolean,
        extraPackages: List<String> = emptyList()
    ) {
        if (!isDeviceOwner()) return
        if (blockBrowsers) knownBrowsers.forEach { setAppBlocked(it, true) }
        if (blockAi) knownAiApps.forEach { setAppBlocked(it, true) }
        if (blockSocial) knownSocial.forEach { setAppBlocked(it, true) }
        if (blockStore) knownStores.forEach { setAppBlocked(it, true) }
        extraPackages.forEach { setAppBlocked(it, true) }
    }

    fun leaveDedicatedMode() {
        if (!isDeviceOwner()) return
        try {
            dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
            dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName)
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------
    // Restrictions globales de l'appareil.
    // ---------------------------------------------------------------

    private val baseRestrictions = listOf(
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_CONFIG_DATE_TIME
    ) + if (Build.VERSION.SDK_INT >= 29) listOf(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        else emptyList()

    fun applyBaseRestrictions() {
        if (!isDeviceOwner()) return
        baseRestrictions.forEach {
            try { dpm.addUserRestriction(adminComponent, it) } catch (e: Exception) { }
        }
        try {
            dpm.setSecureSetting(
                adminComponent,
                android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS,
                "0"
            )
        } catch (e: Exception) { }
    }

    /** Switch from USB setup to the production restrictions on an approved phone. */
    internal fun finishProvisioning(): Boolean {
        if (!isDeviceOwner()) return false
        applyBaseRestrictions()
        val ready = runCatching {
            val restrictions = requireNotNull(users)
            val blockedSources = baseRestrictions.filter { it == UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES ||
                it == UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY }
            blockedSources.forEach { dpm.addUserRestriction(adminComponent, it) }
            if (!blockedSources.all(restrictions::hasUserRestriction)) return@runCatching false

            val storePackages = installationChannels().filter { name ->
                if (name == context.packageName) return@filter false
                try {
                    @Suppress("DEPRECATION")
                    val info = context.packageManager.getApplicationInfo(name, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    info.flags and ApplicationInfo.FLAG_INSTALLED != 0
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            val previous = lockdownPreferences.getStringSet("restore_stores", emptySet()).orEmpty()
            val newlyHidden = storePackages.filterNot { dpm.isApplicationHidden(adminComponent, it) }
            check(lockdownPreferences.edit().putStringSet("protected_stores", storePackages.toSet())
                .putStringSet("restore_stores", previous + newlyHidden).commit())
            if (!storePackages.all { setAppBlocked(it, true) }) return@runCatching false

            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            if (!isDebuggingBlocked()) return@runCatching false
            // The former blanket restriction also prevents the Device Owner from updating apps.
            if (dpm.getUserRestrictions(adminComponent).getBoolean(UserManager.DISALLOW_INSTALL_APPS)) {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            }
            !restrictions.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
        }.getOrDefault(false)
        if (!ready) {
            runCatching { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS) }
            runCatching { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES) }
        }
        return ready
    }

    internal fun removeManagement() = synchronized(chromePolicyLock) {
        removeBaseRestrictions()
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    fun removeBaseRestrictions() {
        if (!isDeviceOwner()) return
        (baseRestrictions + UserManager.DISALLOW_INSTALL_APPS + UserManager.DISALLOW_DEBUGGING_FEATURES).forEach {
            try { dpm.clearUserRestriction(adminComponent, it) } catch (e: Exception) { }
        }
        lockdownPreferences.getStringSet("restore_stores", emptySet()).orEmpty().forEach {
            runCatching { dpm.setApplicationHidden(adminComponent, it, false) }
        }
        lockdownPreferences.edit().clear().apply()
        synchronized(chromePolicyLock) {
            if (chromePreferences.getBoolean(CHROME_CONFIGURED, false)) {
                ChromeManagedPolicy.clear(context, this)
                chromePreferences.edit().clear().apply()
            }
        }
    }

    private companion object {
        const val CHROME_CONFIGURED = "policy_configured"
        val chromePolicyLock = Any()
    }
}
