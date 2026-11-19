package com.yidream.mdm

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogUpdateActionsTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var content: LinearLayout
    private val appPackage = "org.example.approved"

    @Before fun launch() {
        AppAccountDependencies.factory = { FakeAppAccountClient(savedSession = true) }
        AppUpdatesDependencies.factory = { OfflineAppUpdateFixture }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        activity = controller.get()
        content = activity.findViewById(R.id.storeContent)
        content.removeAllViews()
    }

    @After fun finish() {
        controller.pause().stop().destroy()
        AppAccountDependencies.factory = { AppAccountService(it) }
        AppUpdatesDependencies.reset()
    }

    private fun release(pkg: String = appPackage) = JSONObject()
        .put("name", "Approved app")
        .put("package_name", pkg)
        .put("version_code", 2)
        .put("apk_url", "https://example.invalid/approved.apk")
        .put("sha256", "a".repeat(64))
        .put("source_url", "https://play.google.com/store/apps/details?id=$pkg")

    @Test fun approvedButAbsentPackageOffersNoInstallOrExternalStoreAction() {
        CatalogScreenController(activity, MdmService(activity)).renderAppCard(release(), owner = true)
        assertEquals(0, content.childCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun alreadyInstalledApprovedPackageOffersOnlyVerifiedUpdate() {
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
            packageName = appPackage
            versionName = "1"
            @Suppress("DEPRECATION")
            versionCode = 1
            applicationInfo = ApplicationInfo().apply {
                packageName = appPackage
                flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
        val catalog = CatalogScreenController(activity, MdmService(activity))
        catalog.renderAppCard(release(), owner = true)
        val update = descendants(content).filterIsInstance<Button>().single()
        assertEquals(activity.getString(R.string.store_update), update.text)
        assertTrue(update.isEnabled)
        assertNull(shadowOf(activity).nextStartedActivity)

        content.removeAllViews()
        catalog.renderAppCard(release().put("sha256", JSONObject.NULL), owner = true)
        assertFalse(descendants(content).any { it is Button })
        assertTrue(descendants(content).filterIsInstance<TextView>().any {
            it.text == activity.getString(R.string.store_apk_missing)
        })
    }

    @Test fun zemerUpdateIsReachableWithoutTheStoreAndNeverBecomesAnUnapprovedFirstInstall() {
        val pkg = "com.jtech.zemer"
        val updates = activity.findViewById<LinearLayout>(R.id.allowedAppUpdatesContent)
        updates.removeAllViews()
        val catalog = CatalogScreenController(activity, MdmService(activity),
            R.id.allowedAppUpdatesContent, allowFirstInstalls = false)
        catalog.renderAppCard(release(pkg), owner = true)
        assertEquals("The settings update path cannot install an absent app", 0, updates.childCount)
        shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            versionName = "1"
            @Suppress("DEPRECATION") versionCode = 1
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg; flags = ApplicationInfo.FLAG_INSTALLED
            }
        })
        catalog.renderAppCard(release(pkg).put("name", "Zemer"), owner = true)
        val update = descendants(updates).filterIsInstance<Button>().single()
        assertEquals(activity.getString(R.string.store_update), update.text)
        assertTrue(update.hasOnClickListeners())
        assertTrue(update.isEnabled)
        assertEquals("The Store container remains untouched", 0, content.childCount)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) (0 until view.childCount).forEach { addAll(descendants(view.getChildAt(it))) }
    }
}
