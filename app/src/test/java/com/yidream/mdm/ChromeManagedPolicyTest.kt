package com.yidream.mdm

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChromeManagedPolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val chrome = ChromeManagedPolicy.PACKAGE_NAME

    @Test fun savesPermanentRulesWithoutExposingChromeOrErasingOtherSettings() {
        val owner = configureOwner()
        installChrome()
        assertTrue(owner.setAppBlocked(chrome, true))
        owner.dpm.setApplicationRestrictions(owner.adminComponent, chrome, Bundle().apply {
            putInt("IncognitoModeAvailability", 1)
        })
        val policy = DevicePolicy(
            revision = 1,
            apps = mapOf(chrome to true),
            sites = mapOf("example.org" to true, "blocked.example.org" to false),
            temporarySites = mapOf("travel.example.net" to Instant.parse("2099-01-01T00:00:00Z"))
        )

        assertFalse(ChromeManagedPolicy.matches(context, owner, policy))
        assertTrue(ChromeManagedPolicy.apply(context, owner, policy))
        assertTrue(ChromeManagedPolicy.matches(context, owner, policy))
        assertTrue(owner.isAppBlocked(chrome))
        val saved = restrictions(owner)
        assertEquals("[\"*\",\"blocked.example.org\"]", saved.getString("URLBlocklist"))
        val allowed = JSONArray(saved.getString("URLAllowlist"))
        assertEquals(1, allowed.length())
        assertEquals("https://example.org:443", allowed.getString(0))
        assertEquals(1, saved.getInt("IncognitoModeAvailability"))
    }

    @Test fun emptyPermanentRulesBlockAllAndClearPreservesOtherKeys() {
        val owner = configureOwner()
        installChrome()
        owner.dpm.setApplicationRestrictions(owner.adminComponent, chrome, Bundle().apply {
            putInt("BrowserSignin", 0)
        })

        assertTrue(ChromeManagedPolicy.apply(context, owner, DevicePolicy(1, emptyMap(), emptyMap())))
        assertEquals("[\"*\"]", restrictions(owner).getString("URLBlocklist"))
        assertEquals("[]", restrictions(owner).getString("URLAllowlist"))
        assertTrue(ChromeManagedPolicy.clear(context, owner))
        assertTrue(ChromeManagedPolicy.clear(context, owner))
        assertEquals(setOf("BrowserSignin"), restrictions(owner).keySet())
    }

    @Test fun rejectsMissingUnsupportedAndWebViewProviderChrome() {
        val owner = configureOwner()
        val requested = DevicePolicy(1, emptyMap(), emptyMap(), managedChromeEnabled = true)
        assertFalse(ChromeManagedPolicy.canManage(context))
        assertEquals(ManagedChromeStatus.UNSUPPORTED, owner.applyManagedChrome(requested) { true })
        installChrome("85.0.0.0")
        assertFalse(ChromeManagedPolicy.canManage(context))
        val current = installChrome("86.0.0.0")
        assertTrue(ChromeManagedPolicy.canManage(context))
        ShadowWebView.setCurrentWebViewPackage(current)
        try {
            assertFalse(ChromeManagedPolicy.canManage(context))
        } finally {
            ShadowWebView.setCurrentWebViewPackage(null)
        }
    }

    @Test fun refusesInvalidHostsAndNonOwners() {
        installChrome()
        val valid = DevicePolicy(1, emptyMap(), mapOf("example.org" to true))
        assertFalse(ChromeManagedPolicy.apply(context, DeviceOwnerManager(context), valid))

        val owner = configureOwner()
        val invalid = valid.copy(sites = mapOf("*.example.org" to true))
        assertFalse(ChromeManagedPolicy.apply(context, owner, invalid))
        assertTrue(restrictions(owner).isEmpty)
    }

    @Test fun ownerKeepsChromeHiddenUntilTheCurrentDevicePolicyIsApplied() {
        val owner = configureOwner()
        installChrome()
        assertTrue(owner.setAppBlocked(chrome, true))
        val legacy = DevicePolicy(1, mapOf(chrome to true), mapOf("example.org" to true))
        assertEquals(ManagedChromeStatus.READY, owner.applyManagedChrome(legacy) { true })
        assertFalse(owner.canOpenManagedChrome(legacy))
        assertTrue(owner.isAppBlocked(chrome))

        val policy = legacy.copy(apps = mapOf(chrome to false), managedChromeEnabled = true)

        assertEquals(ManagedChromeStatus.FAILED, owner.applyManagedChrome(policy) { false })
        assertTrue(owner.isAppBlocked(chrome))
        assertEquals(ManagedChromeStatus.ACTIVE, owner.applyManagedChrome(policy) { true })
        assertTrue(owner.canOpenManagedChrome(policy))
        assertFalse(owner.isAppBlocked(chrome))

        val invalid = policy.copy(sites = mapOf("*.example.org" to true))
        assertEquals(ManagedChromeStatus.FAILED, owner.applyManagedChrome(invalid) { true })
        assertTrue(owner.isAppBlocked(chrome))
        assertEquals(ManagedChromeStatus.ACTIVE, owner.applyManagedChrome(policy) { true })
        val disabled = policy.copy(managedChromeEnabled = false)
        assertEquals(ManagedChromeStatus.READY, owner.applyManagedChrome(disabled) { true })
        assertFalse(owner.canOpenManagedChrome(disabled))
        assertTrue(owner.isAppBlocked(chrome))
        assertEquals(ManagedChromeStatus.ACTIVE, owner.applyManagedChrome(policy) { true })
        assertTrue(owner.closeManagedChrome())
        assertFalse(owner.canOpenManagedChrome(policy))
        assertTrue(owner.isAppBlocked(chrome))
    }

    private fun configureOwner(): DeviceOwnerManager = DeviceOwnerManager(context).also { owner ->
        shadowOf(owner.dpm).setDeviceOwner(owner.adminComponent)
    }

    private fun installChrome(version: String = "130.0.0.0"): PackageInfo = PackageInfo().apply {
        packageName = chrome
        versionName = version
        applicationInfo = ApplicationInfo().apply {
            packageName = chrome
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED
        }
        shadowOf(context.packageManager).installPackage(this)
    }

    private fun restrictions(owner: DeviceOwnerManager): Bundle =
        owner.dpm.getApplicationRestrictions(owner.adminComponent, chrome)
}
