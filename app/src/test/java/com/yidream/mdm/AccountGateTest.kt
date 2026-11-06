package com.yidream.mdm

import android.content.Context
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
    var requestedEmail: String? = null
    var activations = 0
    override val hasSession get() = savedSession
    val access = AppAccess(AppAccount("test-account", "customer@example.invalid", "+33712345678", true), null)
    override suspend fun validateSession(): AppAccess {
        validationFailure?.let { throw it }
        check(savedSession)
        return access
    }
    override suspend fun requestEmailCode(email: String) { requestedEmail = email }
    override suspend fun verifyEmailCode(email: String, phone: String, code: String): AppAccess { savedSession = true; return access }
    override suspend fun activateCode(email: String, phone: String, code: String): AppAccess { activations++; savedSession = true; return access }
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
        app.getSharedPreferences("yidream_account_session", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("yidream_device_portal", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        AppAccountDependencies.factory = { AppAccountService(it) }
    }
    private fun launch() {
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun freshLaunchNeverInflatesWorkspaceAndOffersWhatsApp() {
        launch()
        assertNull(activity.findViewById<View>(R.id.pageHome))
        assertNotNull(activity.findViewById<View>(R.id.accountScreen))
        assertTrue(activity.findViewById<Button>(R.id.accountWhatsApp).hasOnClickListeners())
        assertFalse(activity.findViewById<Button>(R.id.accountContinue).text.isBlank())
        val selected = activity.findViewById<AppCompatButton>(R.id.accountEmailMode)
        val help = activity.findViewById<AppCompatButton>(R.id.accountWhatsApp)
        assertNull("Theme tint must not override the selected segment drawable", selected.supportBackgroundTintList)
        assertNull("Theme tint must not turn WhatsApp blue behind blue text", help.supportBackgroundTintList)
        assertNull(help.backgroundTintList)
        assertEquals(activity.getColor(R.color.white), selected.currentTextColor)
        assertEquals(activity.getColor(R.color.accent_blue), help.currentTextColor)
        val selectedFill = backgroundColor(selected)
        val helpFill = backgroundColor(help)
        assertEquals(activity.getColor(R.color.accent_blue), selectedFill)
        assertEquals(activity.getColor(R.color.white), helpFill)
        assertTrue("Selected sign-in text must contrast against its actual drawable", ColorUtils.calculateContrast(selected.currentTextColor, selectedFill) >= 4.5)
        assertTrue("WhatsApp text must contrast against its actual drawable", ColorUtils.calculateContrast(help.currentTextColor, helpFill) >= 4.5)
        capture("account-sign-in")
        activity.findViewById<Button>(R.id.accountWhatsApp).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("https://wa.me/33766790352", intent.dataString)
    }

    @Test fun requiresBothContactFieldsBeforeRequestingEmailCode() {
        launch()
        activity.findViewById<EditText>(R.id.accountEmail).setText("customer@example.invalid")
        activity.findViewById<Button>(R.id.accountContinue).performClick()
        assertNull(fake.requestedEmail)
        assertNotNull(activity.findViewById<EditText>(R.id.accountPhone).error)
        activity.findViewById<EditText>(R.id.accountPhone).setText("+33 7 12 34 56 78")
        activity.findViewById<Button>(R.id.accountContinue).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("customer@example.invalid", fake.requestedEmail)
        assertNull("Receiving a code is not authentication", activity.findViewById<View>(R.id.pageHome))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.accountCodeGroup).visibility)
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

    @Test fun administratorCodeRequiresFullRandomCodeAndNeverClaimsSmsVerification() {
        launch()
        activity.findViewById<Button>(R.id.accountCodeMode).performClick()
        activity.findViewById<EditText>(R.id.accountEmail).setText("customer@example.invalid")
        activity.findViewById<EditText>(R.id.accountPhone).setText("+33712345678")
        capture("account-admin-code")
        activity.findViewById<EditText>(R.id.accountCode).setText("123456")
        activity.findViewById<Button>(R.id.accountContinue).performClick()
        assertEquals(0, fake.activations)
        assertNotNull(activity.findViewById<EditText>(R.id.accountCode).error)
        assertFalse(activity.findViewById<EditText>(R.id.accountCode).isSaveEnabled)
        val selected = activity.findViewById<AppCompatButton>(R.id.accountCodeMode)
        assertNull(selected.supportBackgroundTintList)
        assertEquals(activity.getColor(R.color.white), selected.currentTextColor)
    }

    @Test fun tamperedStoredSessionFailsClosedAndIsDiscarded() {
        app.getSharedPreferences("yidream_account_session", Context.MODE_PRIVATE).edit()
            .putString("ciphertext", "invalid").putString("iv", "invalid").commit()
        assertNull(AppSessionStore(app).read())
        assertTrue(app.getSharedPreferences("yidream_account_session", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test @Config(qualifiers = "he-ldrtl-w320dp-h640dp-xhdpi")
    fun hebrewAccountScreenUsesRtlWhileContactInputsStayLtr() {
        launch()
        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.findViewById<View>(R.id.accountScreen).layoutDirection)
        assertEquals(View.TEXT_DIRECTION_LTR, activity.findViewById<View>(R.id.accountEmail).textDirection)
        assertEquals(View.TEXT_DIRECTION_LTR, activity.findViewById<View>(R.id.accountPhone).textDirection)
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
    @Test fun normalizesContactWithoutClaimingItVerified() {
        assertEquals("name@example.com", AccountInput.email(" Name@Example.COM "))
        assertEquals("+33712345678", AccountInput.phone("+33 (7) 12-34-56-78"))
        assertEquals("A".repeat(32), AccountInput.administratorCode("aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa-aaaa"))
    }
    @Test fun rejectsMissingOrUnsafeContactAndWeakCodes() {
        listOf("", "invalid", "a@example.com\nBcc:x@y.com").forEach { assertThrows(IllegalArgumentException::class.java) { AccountInput.email(it) } }
        listOf("0712345678", "+0012345678", "+1", "+33712345678ext4").forEach { assertThrows(IllegalArgumentException::class.java) { AccountInput.phone(it) } }
        listOf("123456", "a".repeat(31), "g".repeat(32)).forEach { assertThrows(IllegalArgumentException::class.java) { AccountInput.administratorCode(it) } }
    }
}
