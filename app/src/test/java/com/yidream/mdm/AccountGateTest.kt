package com.yidream.mdm

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.widget.AppCompatButton
import androidx.core.graphics.ColorUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.io.File
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import android.os.Looper

internal class FakeAppAccountClient(var savedSession: Boolean = false) : AppAccountClient {
    var validationFailure: Exception? = null
    var activationFailure: Exception? = null
    var activations = 0
    override val hasSession get() = savedSession
    val access = AppAccess(AppAccount("test-account", "customer@example.invalid", "+33712345678", true), "90c0e024-62e3-48ec-8c4a-6d148d3745c1")
    override suspend fun validateSession(): AppAccess {
        validationFailure?.let { throw it }
        check(savedSession)
        return access
    }
    override suspend fun activateCode(code: String): AppAccess {
        activations++
        activationFailure?.let { throw it }
        savedSession = true
        return access
    }
    override suspend fun logout() { savedSession = false }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AccountGateTest {
    private lateinit var controller: ActivityController<MainActivity>
    private val fake = FakeAppAccountClient()
    private val app get() = RuntimeEnvironment.getApplication()
    private val activity get() = controller.get()

    @Before fun setUp() {
        AppAccountDependencies.factory = { fake }
        AppUpdatesDependencies.factory = { OfflineAppUpdateFixture }
        app.getSharedPreferences("yidream_account_session", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("yidream_device_portal", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        AppAccountDependencies.factory = { AppAccountService(it) }
        AppUpdatesDependencies.reset()
    }
    private fun launch(intent: Intent = Intent()) {
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun freshLaunchNeverInflatesWorkspaceAndOffersWhatsApp() {
        launch()
        assertNull(activity.findViewById<View>(R.id.pageHome))
        assertNotNull(activity.findViewById<View>(R.id.accountScreen))
        assertTrue(activity.findViewById<Button>(R.id.accountWhatsApp).hasOnClickListeners())
        assertFalse(activity.findViewById<Button>(R.id.accountContinue).text.isBlank())
        val selected = activity.findViewById<AppCompatButton>(R.id.accountContinue)
        val help = activity.findViewById<AppCompatButton>(R.id.accountWhatsApp)
        assertNull("Theme tint must not override the activation button", selected.supportBackgroundTintList)
        assertNull("Theme tint must not turn WhatsApp blue behind blue text", help.supportBackgroundTintList)
        assertNull(help.backgroundTintList)
        assertEquals(activity.getColor(R.color.white), selected.currentTextColor)
        assertEquals(activity.getColor(R.color.accent_blue), help.currentTextColor)
        val selectedFill = backgroundColor(selected)
        val helpFill = backgroundColor(help)
        assertEquals(activity.getColor(R.color.accent_blue), selectedFill)
        assertEquals(activity.getColor(R.color.white), helpFill)
        assertTrue("Activation text must contrast against its actual drawable", ColorUtils.calculateContrast(selected.currentTextColor, selectedFill) >= 4.5)
        assertTrue("WhatsApp text must contrast against its actual drawable", ColorUtils.calculateContrast(help.currentTextColor, helpFill) >= 4.5)
        capture("account-sign-in")
        activity.findViewById<Button>(R.id.accountWhatsApp).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("https://wa.me/33766790352", intent.dataString)
    }

    @Test fun approvedCodeOpensWorkspaceWithoutAnEmailStep() {
        launch()
        activity.findViewById<EditText>(R.id.accountCode).setText("a".repeat(32))
        activity.findViewById<Button>(R.id.accountContinue).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fake.activations)
        assertNotNull(activity.findViewById<View>(R.id.pageHome))
    }

    @Test fun rejectedCodeLeavesWorkspaceLocked() {
        fake.activationFailure = AccountRequestException(400, "INVALID_ACCOUNT_CODE")
        launch()
        activity.findViewById<EditText>(R.id.accountCode).setText("a".repeat(32))
        activity.findViewById<Button>(R.id.accountContinue).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fake.activations)
        assertNull(activity.findViewById<View>(R.id.pageHome))
        assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.accountMessage).visibility)
    }

    @Test fun publicLauncherIntentCannotSupplyActivationCode() {
        launch(Intent().putExtra(UsbActivationActivity.EXTRA, "A".repeat(32)))
        assertEquals(0, fake.activations)
        assertNull(activity.findViewById<View>(R.id.pageHome))
        assertTrue(activity.findViewById<EditText>(R.id.accountCode).text.isEmpty())
        val usbEntry = activity.packageManager.getActivityInfo(ComponentName(activity, UsbActivationActivity::class.java), 0)
        assertEquals("android.permission.DUMP", usbEntry.permission)
    }

    @Test fun protectedUsbHandoffActivatesANewPhoneOnce() {
        UsbActivation.offer("A".repeat(32))
        launch()
        assertEquals(1, fake.activations)
        assertNotNull(activity.findViewById<View>(R.id.pageHome))
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fake.activations)
    }

    @Test fun usbHandoffDoesNotReplaceAnExistingSessionWithoutConfirmation() {
        fake.savedSession = true
        UsbActivation.offer("A".repeat(32))
        launch()
        assertEquals(0, fake.activations)
        assertNull(activity.findViewById<View>(R.id.pageHome))
        activity.findViewById<Button>(R.id.accountKeep).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, fake.activations)
        assertNotNull(activity.findViewById<View>(R.id.pageHome))
    }

    @Test fun cachedSessionCannotExposeWorkspaceWhenValidationFails() {
        fake.savedSession = true
        fake.validationFailure = java.io.IOException("offline")
        launch()
        assertNull(activity.findViewById<View>(R.id.pageHome))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.accountRetry).visibility)
        assertNotNull(activity.findViewById<View>(R.id.accountWhatsApp))
    }

    @Test fun webAdbConfigurationIsConsumedBeforeSignInWithoutWorkspaceViews() {
        val configuration = File(app.getExternalFilesDir(null), "yidream_config.json")
        configuration.parentFile?.mkdirs()
        configuration.writeText("{}")
        launch()
        assertFalse("A valid WebADB import completes while the account screen is locked", configuration.exists())
        assertNotNull(activity.findViewById<View>(R.id.accountScreen))
        assertNull(activity.findViewById<View>(R.id.statusBanner))
        assertNull(activity.findViewById<View>(R.id.pageHome))
    }

    @Test fun foregroundAlwaysRechecksAndHidesPreviouslyValidatedWorkspace() {
        fake.savedSession = true
        launch()
        assertNotNull(activity.findViewById<View>(R.id.pageHome))
        controller.pause()
        assertNull(activity.findViewById<View>(R.id.pageHome))
        fake.validationFailure = AccountRequestException(403, "APP_ACCESS_DENIED")
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(activity.findViewById<View>(R.id.pageHome))
        activity.onBackPressedDispatcher.onBackPressed()
        assertNull("A navigation callback must never reopen a cached workspace while locked", activity.findViewById<View>(R.id.pageHome))
    }

    @Test fun administratorCodeRejectsShortCodesAndIsNotSavedInViewState() {
        launch()
        capture("account-admin-code")
        activity.findViewById<EditText>(R.id.accountCode).setText("123456")
        activity.findViewById<Button>(R.id.accountContinue).performClick()
        assertEquals(0, fake.activations)
        assertNotNull(activity.findViewById<EditText>(R.id.accountCode).error)
        assertFalse(activity.findViewById<EditText>(R.id.accountCode).isSaveEnabled)
    }

    @Test fun tamperedStoredSessionFailsClosedAndIsDiscarded() {
        app.getSharedPreferences("yidream_account_session", Context.MODE_PRIVATE).edit()
            .putString("ciphertext", "invalid").putString("iv", "invalid").commit()
        assertNull(AppSessionStore(app).read())
        assertTrue(app.getSharedPreferences("yidream_account_session", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test @Config(qualifiers = "he-ldrtl-w320dp-h640dp-xhdpi")
    fun hebrewAccountScreenUsesRtlWhileActivationCodeStaysLtr() {
        launch()
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.findViewById<View>(R.id.accountScreen).layoutDirection)
        assertEquals(View.TEXT_DIRECTION_LTR, activity.findViewById<View>(R.id.accountCode).textDirection)
        capture("account-sign-in-rtl")
    }

    private fun capture(name: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val density = activity.resources.displayMetrics.density
        val width = (360 * density).toInt(); val height = (800 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val directory = File(System.getProperty("yidream.uiPreviewDir", "build/ui-previews")).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun backgroundColor(button: Button): Int {
        val background = requireNotNull(button.background.constantState).newDrawable().mutate()
        val bitmap = Bitmap.createBitmap(120, 52, Bitmap.Config.ARGB_8888)
        background.setBounds(0, 0, 120, 52)
        background.draw(Canvas(bitmap))
        val color = bitmap.getPixel(60, 26)
        bitmap.recycle()
        return color
    }
}

class AccountInputTest {
    @Test fun normalizesGroupedActivationCode() {
        assertEquals("A".repeat(32), AccountInput.administratorCode("aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa"))
    }

    @Test fun rejectsWeakOrMalformedCodes() {
        listOf("123456", "a".repeat(31), "g".repeat(32), "a".repeat(32) + "\nextra").forEach {
            assertThrows(IllegalArgumentException::class.java) { AccountInput.administratorCode(it) }
        }
    }
}
