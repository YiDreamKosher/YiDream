package com.yidream.mdm

import android.content.Context
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-w320dp-h640dp-xhdpi")
class CatalogPlayActionsTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var content: LinearLayout
    private val pkg = "org.example.futureapp"

    @Before fun launchWithoutDeviceCredentials() {
        RuntimeEnvironment.getApplication().getSharedPreferences("yidream_device_portal", Context.MODE_PRIVATE)
            .edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        content = activity.findViewById(R.id.storeContent)
        content.removeAllViews()
    }

    @After fun finish() { controller.pause().stop().destroy() }

    private fun draft(source: String) = JSONObject().put("name", "Future approved app")
        .put("package_name", pkg).put("description", "A future catalog entry")
        .put("source_url", source).put("version_code", JSONObject.NULL)
        .put("apk_url", JSONObject.NULL).put("sha256", JSONObject.NULL)

    @Test fun futurePlayAppWithoutAnApkHasAnHonestUnavailableStateAndNoBrowserEscape() {
        CatalogScreenController(activity, MdmService(activity)).renderAppCard(
            draft("https://play.google.com/store/apps/details?id=$pkg"), owner = false)
        val views = descendants(content)
        val play = views.filterIsInstance<Button>().single { it.text == activity.getString(R.string.store_check_play) }
        assertFalse("A filtered or Play-less phone must not offer a nonfunctional install", play.isEnabled)
        assertTrue(views.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.store_apk_missing) })
        assertTrue(views.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.store_play_unavailable) })
        assertFalse(views.filterIsInstance<TextView>().any { it.text == activity.getString(R.string.store_catalog_current) })
        // Even an explicit synthetic click cannot launch an external browser.
        play.performClick()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(MdmService(activity).paired)
    }

    @Test fun mismatchedSourceCannotExposeAnotherApplicationsPlayListing() {
        CatalogScreenController(activity, MdmService(activity)).renderAppCard(
            draft("https://play.google.com/store/apps/details?id=com.whatsapp"), owner = false)
        assertFalse(descendants(content).filterIsInstance<Button>()
            .any { it.text == activity.getString(R.string.store_check_play) })
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) (0 until view.childCount).forEach { addAll(descendants(view.getChildAt(it))) }
    }
}
