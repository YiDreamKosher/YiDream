package com.yidream.mdm

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebView
import org.json.JSONArray
import java.net.URI

internal enum class ManagedChromeStatus(val wireValue: String) {
    READY("ready"),
    ACTIVE("active"),
    UNSUPPORTED("unsupported"),
    FAILED("failed")
}

/** Projects permanent YiDream site rules into Chrome's managed configuration. */
internal object ChromeManagedPolicy {
    const val PACKAGE_NAME = "com.android.chrome"
    private const val MIN_CHROME_MAJOR = 86
    private const val URL_BLOCKLIST = "URLBlocklist"
    private const val URL_ALLOWLIST = "URLAllowlist"
    private const val MAX_LIST_LENGTH = 1000

    fun canManage(context: Context): Boolean = runCatching {
        if (WebView.getCurrentWebViewPackage()?.packageName == PACKAGE_NAME) return false
        @Suppress("DEPRECATION")
        val chrome = context.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        val app = chrome.applicationInfo ?: return false
        val major = chrome.versionName?.substringBefore('.')?.toIntOrNull() ?: return false
        app.enabled && (app.flags and ApplicationInfo.FLAG_INSTALLED) != 0 && major >= MIN_CHROME_MAJOR
    }.getOrDefault(false)

    /** A true result verifies Android's saved bundle, not Chrome's navigation enforcement. */
    @Synchronized
    fun apply(context: Context, owner: DeviceOwnerManager, policy: DevicePolicy): Boolean {
        if (!owner.isDeviceOwner() || !canManage(context)) return false
        return runCatching {
            val (blocklist, allowlist) = permanentLists(policy)
            val saved = owner.dpm.getApplicationRestrictions(owner.adminComponent, PACKAGE_NAME)
            if (matches(saved, blocklist, allowlist)) return true
            val updated = Bundle(saved).apply {
                // Chrome's managed configuration declares these lists as JSON strings.
                putString(URL_BLOCKLIST, blocklist)
                putString(URL_ALLOWLIST, allowlist)
            }
            owner.dpm.setApplicationRestrictions(owner.adminComponent, PACKAGE_NAME, updated)
            matches(owner.dpm.getApplicationRestrictions(owner.adminComponent, PACKAGE_NAME), blocklist, allowlist)
        }.getOrDefault(false)
    }

    @Synchronized
    fun matches(context: Context, owner: DeviceOwnerManager, policy: DevicePolicy): Boolean {
        if (!owner.isDeviceOwner() || !canManage(context)) return false
        return runCatching {
            val (blocklist, allowlist) = permanentLists(policy)
            matches(owner.dpm.getApplicationRestrictions(owner.adminComponent, PACKAGE_NAME), blocklist, allowlist)
        }.getOrDefault(false)
    }

    @Synchronized
    fun clear(context: Context, owner: DeviceOwnerManager): Boolean {
        if (!owner.isDeviceOwner()) return false
        return runCatching {
            val saved = owner.dpm.getApplicationRestrictions(owner.adminComponent, PACKAGE_NAME)
            if (!saved.containsKey(URL_BLOCKLIST) && !saved.containsKey(URL_ALLOWLIST)) return true
            val updated = Bundle(saved).apply {
                remove(URL_BLOCKLIST)
                remove(URL_ALLOWLIST)
            }
            owner.dpm.setApplicationRestrictions(owner.adminComponent, PACKAGE_NAME, updated)
            val current = owner.dpm.getApplicationRestrictions(owner.adminComponent, PACKAGE_NAME)
            !current.containsKey(URL_BLOCKLIST) && !current.containsKey(URL_ALLOWLIST)
        }.getOrDefault(false)
    }

    private fun permanentLists(policy: DevicePolicy): Pair<String, String> {
        val blocked = JSONArray().put("*")
        val allowed = JSONArray()
        // Chrome has no per-rule expiry. Temporary access stays in the YiDream reader.
        for ((host, permit) in policy.sites.toSortedMap()) {
            val address = URI("https", null, host, 443, null, null, null)
            require(address.host == host && DevicePolicy.isWebAddress(address))
            if (permit) allowed.put(address.toASCIIString()) else blocked.put(host)
        }
        require(blocked.length() <= MAX_LIST_LENGTH && allowed.length() <= MAX_LIST_LENGTH)
        return blocked.toString() to allowed.toString()
    }

    private fun matches(restrictions: Bundle, blocklist: String, allowlist: String): Boolean =
        restrictions.getString(URL_BLOCKLIST) == blocklist && restrictions.getString(URL_ALLOWLIST) == allowlist
}
