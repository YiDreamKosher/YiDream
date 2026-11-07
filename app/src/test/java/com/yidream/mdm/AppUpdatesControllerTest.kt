package com.yidream.mdm

import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class AppUpdatesControllerTest {
    private lateinit var activityController: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity
    private lateinit var content: LinearLayout
    private lateinit var updates: AppUpdatesController
    private val client = FakeClient()

    private class FakeClient : AppUpdateClient {
        var checkResult: UpdateManager.CheckResult = UpdateManager.CheckResult.Available(
            UpdateManager.UpdateInfo(Int.MAX_VALUE, "v99.0.0", UpdateManager.APPROVED_APK_URL,
                "a".repeat(64), "A verified test update."))
        var checks = 0
        var installs = 0
        val installation = CompletableDeferred<SilentInstaller.Result>()
        override suspend fun check(): UpdateManager.CheckResult { checks++; return checkResult }
        override suspend fun install(update: UpdateManager.UpdateInfo): SilentInstaller.Result {
            installs++
            return installation.await()
        }
    }

    @Before fun setUp() {
        activityController = Robolectric.buildActivity(AppCompatActivity::class.java).create().start().resume().visible()
        activity = activityController.get()
        activity.getSharedPreferences("yidream_optional_updates", 0).edit().clear().commit()
        content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(content)
        updates = AppUpdatesController(activity, client) { 100_000L }
    }

    @After fun tearDown() {
        if (::updates.isInitialized) updates.cancel()
        if (::activityController.isInitialized) activityController.pause().stop().destroy()
        AppUpdatesDependencies.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun state() = activity.findViewById<TextView>(R.id.appUpdateState).text.toString()

    @Test fun checkingAnAvailableUpdateNeverInstallsIt() {
        updates.mount(content)
        idle()
        assertEquals(1, client.checks)
        assertEquals(0, client.installs)
        assertEquals(activity.getString(R.string.app_update_available, "v99.0.0"), state())
        assertTrue(activity.findViewById<Button>(R.id.appUpdateNow).isEnabled)
        assertNotNull(activity.findViewById<View>(R.id.appUpdateLater))
    }

    @Test fun oneExplicitClickInstallsAndWaitsForAndroidConfirmation() {
        updates.mount(content)
        idle()
        val button = activity.findViewById<Button>(R.id.appUpdateNow)
        button.performClick()
        button.performClick()
        idle()
        assertEquals("Repeated presses cannot submit a second installation", 1, client.installs)
        assertEquals(activity.getString(R.string.app_update_installing), state())
        assertNotNull(activity.findViewById<View>(R.id.appUpdateProgress))
        assertNull(activity.findViewById<View>(R.id.appUpdateNow))
        client.installation.complete(SilentInstaller.Result.Success)
        idle()
        assertEquals(activity.getString(R.string.app_update_success), state())
        assertNull(activity.findViewById<View>(R.id.appUpdateNow))
    }

    @Test fun unavailableCheckDoesNotClaimTheAppIsCurrent() {
        client.checkResult = UpdateManager.CheckResult.Unavailable
        updates.mount(content)
        idle()
        assertEquals(activity.getString(R.string.app_update_unavailable), state())
        assertNotEquals(activity.getString(R.string.app_update_current), state())
        assertNull(activity.findViewById<View>(R.id.appUpdateNow))
        assertNotNull(activity.findViewById<View>(R.id.appUpdateCheck))
        assertEquals(0, client.installs)
    }

    @Test fun installationFailureRemainsAVisibleFailureWithAnOptionalRetry() {
        updates.mount(content)
        idle()
        activity.findViewById<Button>(R.id.appUpdateNow).performClick()
        client.installation.complete(SilentInstaller.Result.Failure("Android rejected the signature."))
        idle()
        assertEquals(activity.getString(R.string.app_update_failed), state())
        assertTrue(activity.findViewById<Button>(R.id.appUpdateNow).isEnabled)
        assertFalse(state().contains(activity.getString(R.string.app_update_success)))
    }

    @Test fun choosingLaterSurvivesReopeningWithoutAnAutomaticInstall() {
        updates.mount(content)
        idle()
        activity.findViewById<Button>(R.id.appUpdateLater).performClick()
        assertEquals(activity.getString(R.string.app_update_deferred, "v99.0.0"), state())
        updates.cancel()
        updates = AppUpdatesController(activity, client) { 100_001L }
        var badge: Boolean? = null
        updates.checkAvailability { badge = it }
        idle()
        assertEquals(false, badge)
        assertEquals("A recent background result is reused", 1, client.checks)
        assertEquals(0, client.installs)
        updates.mount(content)
        idle()
        assertNotNull("The user can still choose to update from Settings", activity.findViewById<View>(R.id.appUpdateNow))
        assertEquals(0, client.installs)
    }

    @Test fun backgroundAvailabilityOnlyUpdatesTheBadgeAndCreatesNoScreen() {
        var badge = false
        updates.checkAvailability { badge = it }
        idle()
        assertTrue(badge)
        assertEquals(0, content.childCount)
        assertEquals(0, client.installs)
    }
}
