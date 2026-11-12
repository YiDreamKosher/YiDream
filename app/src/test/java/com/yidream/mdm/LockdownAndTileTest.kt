package com.yidream.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.UserManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDevicePolicyManager

/** Supply the owning admin's restriction view, which the standard shadow omits. */
@Implements(DevicePolicyManager::class)
class SingleAdminRestrictionsShadow : ShadowDevicePolicyManager() {
    @Implementation
    protected fun getUserRestrictions(admin: ComponentName): Bundle {
        val context = RuntimeEnvironment.getApplication()
        check(admin.packageName == context.packageName)
        return requireNotNull(context.getSystemService(UserManager::class.java)).userRestrictions
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], shadows = [SingleAdminRestrictionsShadow::class])
class LockdownAndTileTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = requireNotNull(context.getSystemService(UserManager::class.java))
    private val store = "com.android.vending"
    private val launcher get() = ComponentName(context.packageName, "${context.packageName}.YiDreamLauncher")

    @Test fun approvalAppliesChannelRestrictionsAndClearsLegacyInstallLock() {
        val owner = configureOwner(legacyInstallLock = true)
        installStore()
        owner.applyBaseRestrictions()
        assertFalse(users.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES))
        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))

        assertTrue(owner.finishProvisioning())

        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES))
        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES))
        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY))
        assertFalse(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))
        assertTrue(owner.isAppBlocked(store))
        assertFalse(owner.setAppBlocked(store, false))
        assertTrue(owner.isAppBlocked(store))
    }

    @Test fun failedStoreHideKeepsLegacyInstallLockAndBlocksDebugging() {
        val owner = configureOwner(legacyInstallLock = true)
        installStore()
        shadowOf(owner.dpm).failSetApplicationHiddenFor(listOf(store))

        assertFalse(owner.finishProvisioning())

        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))
        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES))
        assertFalse(owner.isAppBlocked(store))
    }

    @Test fun failedStoreHideBlocksInstallationsAndDebuggingOnFreshDevice() {
        val owner = configureOwner()
        installStore()
        shadowOf(owner.dpm).failSetApplicationHiddenFor(listOf(store))
        assertFalse(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))

        assertFalse(owner.finishProvisioning())

        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS))
        assertTrue(users.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES))
    }

    @Test fun usbProfileHidesEligibleAppsPreservesUnknownSystemAndCanReopenADeviceApp() {
        val owner = configureOwner()
        installStore()
        val waze = "com.waze"
        val player = "com.rhmsoft.pulsar"
        val unknownSystem = "com.vendor.system"
        val previouslyHidden = "com.example.previouslyhidden"
        installLaunchable(waze, ApplicationInfo.FLAG_SYSTEM)
        installLaunchable(player)
        installLaunchable(unknownSystem, ApplicationInfo.FLAG_SYSTEM)
        installLaunchable(previouslyHidden)
        assertTrue(owner.setAppBlocked(previouslyHidden, true))

        assertTrue(owner.applyUsbAppProfile(UsbAppProfile(UsbAppProfileType.MP3, setOf(player, previouslyHidden))))
        assertTrue(owner.isAppBlocked(waze))
        assertTrue(owner.isAppBlocked(store))
        assertFalse(owner.isAppBlocked(player))
        assertFalse(owner.isAppBlocked(unknownSystem))
        assertFalse(owner.isDebuggingBlocked())
        assertFalse(owner.applyRemoteAppRule(store, allowed = true))
        assertTrue(owner.applyRemoteAppRule(waze, allowed = true))
        assertFalse(owner.isAppBlocked(waze))
        assertTrue(owner.applyRemoteAppRule(waze, allowed = false))

        owner.removeBaseRestrictions()
        assertFalse(owner.isAppBlocked(waze))
        assertTrue(owner.isAppBlocked(previouslyHidden))
    }

    @Test fun usbProfileKeepsDebuggingAvailableWhenAnEligiblePackageCannotBeHidden() {
        val owner = configureOwner()
        val packageName = "com.example.unhideable"
        installLaunchable(packageName)
        shadowOf(owner.dpm).failSetApplicationHiddenFor(listOf(packageName))

        assertFalse(owner.applyUsbAppProfile(UsbAppProfile(UsbAppProfileType.QIN, emptySet())))
        assertFalse(owner.isDebuggingBlocked())
        assertFalse(owner.isAppBlocked(packageName))
    }

    @Test fun launcherStaysVisibleUntilTileIsConfirmedAndReturnsWhenTileIsRemoved() {
        val launch = QuickSettingsLaunch(context)
        assertFalse(launch.hideWhenReady())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getComponentEnabledSetting(launcher))

        launch.rememberTile(true)
        assertTrue(launch.hideWhenReady())
        assertTrue(QuickSettingsLaunch(context).launcherHidden)

        launch.rememberTile(false)
        assertFalse(QuickSettingsLaunch(context).hideWhenReady())
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            context.packageManager.getComponentEnabledSetting(launcher))
    }

    private fun configureOwner(legacyInstallLock: Boolean = false): DeviceOwnerManager {
        val owner = DeviceOwnerManager(context)
        shadowOf(owner.dpm).setDeviceOwner(owner.adminComponent)
        if (legacyInstallLock) {
            owner.dpm.addUserRestriction(owner.adminComponent, UserManager.DISALLOW_INSTALL_APPS)
        }
        return owner
    }

    private fun installLaunchable(name: String, additionalFlags: Int = 0) {
        val app = ApplicationInfo().apply {
            packageName = name
            flags = ApplicationInfo.FLAG_INSTALLED or additionalFlags
            enabled = true
        }
        val activity = ActivityInfo().apply {
            packageName = name
            this.name = "$name.MainActivity"
            applicationInfo = app
            enabled = true
            exported = true
        }
        val info = PackageInfo().apply {
            packageName = name
            applicationInfo = app
            activities = arrayOf(activity)
        }
        val packages = shadowOf(context.packageManager)
        packages.installPackage(info)
        packages.addIntentFilterForActivity(
            ComponentName(name, activity.name),
            android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
    }

    private fun installStore() {
        val info = PackageInfo().apply {
            packageName = store
            applicationInfo = ApplicationInfo().apply {
                packageName = store
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        }
        shadowOf(context.packageManager).installPackage(info)
    }
}
