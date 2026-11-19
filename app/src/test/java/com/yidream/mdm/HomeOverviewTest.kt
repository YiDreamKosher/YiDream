package com.yidream.mdm

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
@Config(sdk = [34], qualifiers = "fr-w240dp-h320dp-mdpi")
class HomeOverviewTest {
    private lateinit var lifecycle: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var overview: HomeOverviewController
    private val target get() = activity.findViewById<LinearLayout>(R.id.homeAppsContent)

    @Before fun launch() {
        AppAccountDependencies.factory = { FakeAppAccountClient(savedSession = true) }
        AppUpdatesDependencies.factory = { OfflineAppUpdateFixture }
        lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        activity = lifecycle.get()
        overview = HomeOverviewController(activity)
    }
    @After fun finish() {
        lifecycle.pause().stop().destroy()
        AppAccountDependencies.factory = { AppAccountService(it) }
        AppUpdatesDependencies.reset()
    }
    private fun labels() = descendants(target).filterIsInstance<TextView>().map { it.text.toString() }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) (0 until view.childCount).forEach { addAll(descendants(view.getChildAt(it))) }
    }
    @Suppress("DEPRECATION")
    private fun install(pkg: String, label: String) {
        val app = ApplicationInfo().apply {
            packageName = pkg; nonLocalizedLabel = label; enabled = true; flags = ApplicationInfo.FLAG_INSTALLED
        }
        val entry = ActivityInfo().apply {
            packageName = pkg; name = "$pkg.MainActivity"; applicationInfo = app; enabled = true; exported = true
        }
        val packages = shadowOf(activity.packageManager)
        packages.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = app; activities = arrayOf(entry) })
        packages.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ResolveInfo().apply { activityInfo = entry })
    }

    @Test fun unknownPolicyShowsASyncNoticeWithoutInventedApps() {
        install("com.waze", "Waze")
        overview.refresh(null)
        assertEquals(listOf(activity.getString(R.string.home_apps_waiting)), labels())
        assertFalse(descendants(target).any { it.isClickable })
    }

    @Test fun mp3OverviewListsOnlyInstalledAllowedAppsAndReevaluatesPolicyChanges() {
        install("com.rhmsoft.pulsar", "Pulsar")
        install("com.waze", "Waze")
        install("com.example.unapproved", "Unapproved")
        install("com.android.vending", "Play Store")
        overview.refresh(DevicePolicy(1, emptyMap(), emptyMap(), deviceProfile = "mp3"))
        assertEquals(listOf("Pulsar"), labels())
        overview.refresh(DevicePolicy(2, mapOf("com.rhmsoft.pulsar" to false), emptyMap(), deviceProfile = "qin"))
        assertEquals(listOf("Waze"), labels())
        overview.refresh(DevicePolicy(3, mapOf("com.example.unapproved" to true), emptyMap(), deviceProfile = "mp3"))
        assertEquals(setOf("Pulsar", "Unapproved"), labels().toSet())
        assertEquals("Small 2.8 inch screens use one column", 2, target.childCount)
    }

    @Test fun appShortcutFailsClosedWhenItsPolicyCannotBeConfirmedAtTapTime() {
        install("com.waze", "Waze")
        overview.refresh(DevicePolicy(1, emptyMap(), emptyMap(), deviceProfile = "qin"))
        val tile = descendants(target).single { it.isClickable }
        assertTrue(tile.performClick())
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
