package com.yidream.mdm

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
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

    @Test fun homeNavigationPreferenceSurvivesActivityRecreationAndNeverTrapsUser() {
        activity.findViewById<View>(R.id.homeOpenSettings).performClick()
        activity.findViewById<View>(R.id.settingsOpenAppearance).performClick()
        activity.findViewById<RadioGroup>(R.id.navigationOptions).check(R.id.navigationHomeOption)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.navigationBar).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.compactNavigation).visibility)
        activity.findViewById<View>(R.id.backToHome).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageHome).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.compactNavigation).visibility)

        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        activity = controller.get()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.navigationBar).visibility)
        listOf(R.id.homeOpenStore to R.id.pageStore, R.id.homeOpenSites to R.id.pageSites,
            R.id.homeOpenSettings to R.id.pageSettings).forEach { (shortcut, page) ->
            activity.findViewById<View>(shortcut).performClick()
            assertEquals(View.VISIBLE, activity.findViewById<View>(page).visibility)
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageHome).visibility)
        }
        activity.findViewById<View>(R.id.homeOpenSettings).performClick()
        activity.findViewById<View>(R.id.settingsOpenAppearance).performClick()
        activity.findViewById<RadioGroup>(R.id.navigationOptions).check(R.id.navigationTabsOption)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.navigationBar).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.compactNavigation).visibility)
    }

    @Test fun logoOnlyAppearsOnHomeAndFiveSettingsGroupsRemainSeparate() {
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
        activity.findViewById<View>(R.id.settingsOpenFilter).performClick()
        activity.findViewById<View>(R.id.rowRemoveFilter).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("Removal opens the existing unlock dialog", dialog)
        listOf(R.id.codeBox0, R.id.codeBox1, R.id.codeBox2, R.id.codeBox3, R.id.codeBox4, R.id.codeBox5)
            .forEach { assertNotNull(dialog.findViewById<View>(it)) }
        assertFalse("No unlock key has been configured in this fixture", UnlockManager(activity).verify("000000"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        // AlertController posts the dismiss operation to the main looper.
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertFalse("Navigation does not enroll a device", MdmService(activity).paired)
    }

    @Test fun settingsOpenOneSubmenuAndBackReturnsToMenuBeforeHome() {
        activity.findViewById<View>(R.id.homeOpenSettings).performClick()
        val destinations = listOf(R.id.settingsOpenAccount to R.id.settingsPageAccount,
            R.id.settingsOpenAppearance to R.id.settingsPageAppearance,
            R.id.settingsOpenLanguage to R.id.settingsPageLanguage,
            R.id.rowUpdates to R.id.settingsPageUpdates,
            R.id.settingsOpenFilter to R.id.settingsPageFilter,
            R.id.settingsOpenContact to R.id.settingsPageContact)
        destinations.forEach { (row, page) ->
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsMenu).visibility)
            activity.findViewById<View>(row).performClick()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsMenu).visibility)
            destinations.forEach { (_, otherPage) -> assertEquals(if (otherPage == page) View.VISIBLE else View.GONE,
                activity.findViewById<View>(otherPage).visibility) }
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals("Back first opens the settings landing list", View.VISIBLE, activity.findViewById<View>(R.id.settingsMenu).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageSettings).visibility)
        }
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pageHome).visibility)
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
