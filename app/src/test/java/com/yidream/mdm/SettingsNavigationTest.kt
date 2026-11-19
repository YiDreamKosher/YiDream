package com.yidream.mdm

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatRadioButton
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-w320dp-h640dp-xhdpi")
class SettingsNavigationTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before fun launch() {
        AppAccountDependencies.factory = { FakeAppAccountClient(savedSession = true) }
        AppUpdatesDependencies.factory = { OfflineAppUpdateFixture }
        val application = RuntimeEnvironment.getApplication()
        listOf("yidream_appearance", "yidream_device_portal", "yidream_unlock").forEach {
            application.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        activity = controller.get()
    }

    @After fun finish() {
        controller.pause().stop().destroy()
        AppAccountDependencies.factory = { AppAccountService(it) }
        AppUpdatesDependencies.reset()
        RuntimeEnvironment.getApplication().getSharedPreferences("yidream_appearance", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun fixedTopTabsRemainAvailableAfterRecreationWithoutANavigationModeSetting() {
        activity.findViewById<View>(R.id.tabSettings).performClick()
        activity.findViewById<View>(R.id.settingsOpenAppearance).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.navigationBar).visibility)
        assertTrue(activity.findViewById<View>(R.id.quickSettingsLauncher).hasOnClickListeners())
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        activity = controller.get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.navigationBar).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsPageAppearance).visibility)
        listOf(R.id.tabHome to R.id.pageHome, R.id.tabStore to R.id.pageStore,
            R.id.tabSites to R.id.pageSites, R.id.tabSettings to R.id.pageSettings).forEach { (tab, page) ->
            activity.findViewById<View>(tab).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(page).visibility)
        }
    }

    @Test fun remoteMp3SectionsHideTheirTabsAndHomeShortcutsAndCanBeEnabledIndependently() {
        fun update(policy: DevicePolicy) {
            MainActivity::class.java.getDeclaredMethod("updateRequestNavigation", DevicePolicy::class.java)
                .apply { isAccessible = true }.invoke(activity, policy)
        }
        activity.findViewById<View>(R.id.tabStore).performClick()
        val mp3 = DevicePolicy(1, emptyMap(), emptyMap(), deviceProfile = "mp3")
        update(mp3)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageHome).visibility)
        listOf(R.id.tabStore, R.id.tabSites, R.id.homeOpenStore, R.id.homeOpenSites).forEach {
            assertEquals(View.GONE, activity.findViewById<View>(it).visibility)
        }
        val pager = activity.findViewById<SwipeWorkspaceLayout>(R.id.workspacePager)
        pager.onPageSwipe?.invoke(true)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageSettings).visibility)
        update(mp3.copy(revision = 2, webSectionEnabled = true))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tabSites).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.tabStore).visibility)
        update(mp3.copy(revision = 3, appStoreSectionEnabled = true))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tabStore).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.tabSites).visibility)
    }

    @Test fun languageChoicesAreFourFullWidthRows() {
        activity.findViewById<View>(R.id.tabSettings).performClick()
        activity.findViewById<View>(R.id.settingsOpenLanguage).performClick()
        val list = activity.findViewById<LinearLayout>(R.id.languageRow)
        assertEquals(LinearLayout.VERTICAL, list.orientation)
        assertEquals(4, list.childCount)
        assertEquals(listOf("English", "Français", "עברית", "ייִדיש"),
            (0 until list.childCount).map { (list.getChildAt(it) as AppCompatRadioButton).text.toString() })
        (0 until list.childCount).forEach { index ->
            val row = list.getChildAt(index) as AppCompatRadioButton
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, row.layoutParams.width)
            assertTrue(row.minHeight >= (48 * activity.resources.displayMetrics.density).toInt())
            assertTrue(row.hasOnClickListeners())
        }
    }

    @Test fun logoOnlyAppearsOnHomeAndExistingSettingsActionsRemainAvailable() {
        val home = activity.findViewById<ViewGroup>(R.id.pageHome)
        val logo = activity.findViewById<View>(R.id.homeLogo)
        assertSame(logo, home.findViewById<View>(R.id.homeLogo))
        assertTrue(logo.isShown)
        listOf(R.id.tabStore, R.id.tabSites, R.id.tabSettings).forEach { tab ->
            activity.findViewById<View>(tab).performClick()
            assertFalse("Logo must not appear on another screen", logo.isShown)
        }
        listOf(R.id.sectionAppearance, R.id.languageRow, R.id.sectionAbout,
            R.id.sectionFilter, R.id.settingsContactContent).forEach { id ->
            assertNotNull(activity.findViewById<ViewGroup>(R.id.pageSettings).findViewById<View>(id))
        }
    }

    @Test fun filterRemovalStillRequiresTheAdministratorUnlockCode() {
        activity.findViewById<View>(R.id.homeOpenSettings).performClick()
        activity.findViewById<View>(R.id.settingsOpenSupport).performClick()
        activity.findViewById<View>(R.id.settingsOpenFilter).performClick()
        activity.findViewById<View>(R.id.rowRemoveFilter).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Removal opens the existing unlock dialog", dialog)
        listOf(R.id.codeBox0, R.id.codeBox1, R.id.codeBox2, R.id.codeBox3, R.id.codeBox4, R.id.codeBox5)
            .forEach { assertNotNull(dialog.findViewById<View>(it)) }
        assertEquals("No unlock key has been configured in this fixture",
            UnlockManager.Verification.INCORRECT, UnlockManager(activity).verify("000000"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        // AlertController posts the dismiss operation to the main looper.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertFalse("Navigation does not enroll a device", MdmService(activity).paired)
    }

    @Test fun supportGroupsUpdatesFilterAndContactWithPredictableBackNavigation() {
        activity.findViewById<View>(R.id.tabSettings).performClick()
        listOf(R.id.settingsOpenAccount to R.id.settingsPageAccount,
            R.id.settingsOpenAppearance to R.id.settingsPageAppearance,
            R.id.settingsOpenLanguage to R.id.settingsPageLanguage).forEach { (row, page) ->
            activity.findViewById<View>(row).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(page).visibility)
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsMenu).visibility)
        }
        activity.findViewById<View>(R.id.settingsOpenSupport).performClick()
        listOf(R.id.rowUpdates to R.id.settingsPageUpdates,
            R.id.settingsOpenFilter to R.id.settingsPageFilter,
            R.id.settingsOpenContact to R.id.settingsPageContact).forEach { (row, page) ->
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsPageSupport).visibility)
            assertTrue(activity.findViewById<View>(row).isShown)
            activity.findViewById<View>(row).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(page).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsPageSupport).visibility)
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsPageSupport).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(page).visibility)
        }
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsMenu).visibility)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageHome).visibility)
    }

    @Test fun updateBadgeRemainsVisibleOnBothSupportAndUpdateDestinations() {
        val menu = SettingsMenuController(activity) {}
        menu.setUpdateAvailable(true)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsToolsUpdateBadge).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsUpdateBadge).visibility)
        menu.setUpdateAvailable(false)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsToolsUpdateBadge).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsUpdateBadge).visibility)
    }

    @Test fun restoredSubmenuWaitsForAccountValidationAndKeepsItsDestination() {
        activity.findViewById<View>(R.id.homeOpenSettings).performClick()
        activity.findViewById<View>(R.id.settingsOpenLanguage).performClick()
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        activity = controller.get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageSettings).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsPageLanguage).visibility)
        activity.findViewById<View>(R.id.settingsBack).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsMenu).visibility)
    }

    @Test fun restoredSettingsNeverAppearWhenAccountValidationFails() {
        activity.findViewById<View>(R.id.homeOpenSettings).performClick()
        activity.findViewById<View>(R.id.settingsOpenAccount).performClick()
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        AppAccountDependencies.factory = { FakeAppAccountClient(savedSession = true).apply {
            validationFailure = java.io.IOException("Test connection unavailable")
        } }
        controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        activity = controller.get()
        assertNotNull(activity.findViewById<View>(R.id.accountScreen))
        assertNull(activity.findViewById<View>(R.id.pageSettings))
        assertNull(activity.findViewById<View>(R.id.accountIdentity))
        assertNull(activity.findViewById<View>(R.id.settingsBack))
    }
}
