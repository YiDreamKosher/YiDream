package com.yidream.mdm

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import java.io.File
import android.widget.Button
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-w360dp-h800dp-xhdpi")
class AdminContactControllerTest {
    private lateinit var lifecycle: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var contact: LinearLayout

    @Before
    fun createActivity() {
        AppAccountDependencies.factory = { FakeAppAccountClient(savedSession = true) }
        AppUpdatesDependencies.factory = { OfflineAppUpdateFixture }
        lifecycle = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        activity = lifecycle.get()
        contact = activity.findViewById(R.id.settingsContactContent)
    }

    @After
    fun destroyActivity() {
        lifecycle.pause().stop().destroy()
        AppAccountDependencies.factory = { AppAccountService(it) }
        AppUpdatesDependencies.reset()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun contactSubmenuPreviewKeepsContactAndInformationActions() {
        activity.findViewById<View>(R.id.homeOpenSettings).performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsMenu).visibility)
        activity.findViewById<View>(R.id.settingsOpenContact).performClick()
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(800), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, dp(360), dp(800))
        val content = activity.findViewById<ScrollView>(R.id.pageSettings).getChildAt(0)
        fun descendants(view: View): List<View> = buildList {
            add(view)
            if (view is ViewGroup) (0 until view.childCount).forEach { addAll(descendants(view.getChildAt(it))) }
        }
        val labels = descendants(content).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(labels.contains(activity.getString(R.string.settings_menu_contact)))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsPageAccount).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.settingsPageFilter).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsPageContact).visibility)
        listOf(R.id.contactWhatsappButton, R.id.contactLabel, R.id.contactUsageButton).forEach { id ->
            val button = activity.findViewById<Button>(id)
            assertTrue(button.hasOnClickListeners())
            assertTrue("Contact actions keep a touch target of at least 48dp", button.height >= dp(48))
            assertTrue(button.width >= dp(48))
        }
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        activity.getDrawable(R.drawable.bg_app_gradient)?.apply {
            setBounds(0, 0, content.width, content.height)
            draw(canvas)
        }
        content.draw(canvas)
        val output = File(System.getProperty("yidream.uiPreviewDir") ?: "build/ui-previews").apply { mkdirs() }
        File(output, "settings-contact.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test
    fun bindingDoesNotOpenAnyExternalServiceAndClickOpensOnlyTheChosenConversation() {
        val opened = mutableListOf<Intent>()
        AdminContactController(activity, contact) { opened += it }.bind()
        assertTrue(opened.isEmpty())
        activity.findViewById<Button>(R.id.contactWhatsappButton).performClick()
        assertEquals(1, opened.size)
        val intent = opened.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https", intent.data!!.scheme)
        assertEquals("wa.me", intent.data!!.host)
        assertEquals("/33766790352", intent.data!!.path)
        assertEquals(setOf("text"), intent.data!!.queryParameterNames)
        assertEquals(activity.getString(R.string.contact_whatsapp_message), intent.data!!.getQueryParameter("text"))
        assertNull("The official link may be opened by WhatsApp or the user's browser", intent.`package`)
        assertNull("Opening a chat never submits a SEND or SENDTO message payload", intent.extras)
    }

    @Test
    fun whatsappUriEncodesMessageWithoutChangingDestinationOrAddingParameters() {
        val message = "שלום & Bonjour ? + = https://example.invalid/?token=private"
        val intent = AdminContactLinks.whatsapp(message)
        assertEquals("wa.me", intent.data!!.host)
        assertEquals("/33766790352", intent.data!!.path)
        assertEquals(message, intent.data!!.getQueryParameter("text"))
        assertEquals(setOf("text"), intent.data!!.queryParameterNames)
    }

    @Test
    fun emailUsesTheExistingAddressWithoutDeviceOrAccountDetails() {
        val opened = mutableListOf<Intent>()
        AdminContactController(activity, contact) { opened += it }.bind()
        activity.findViewById<Button>(R.id.contactLabel).performClick()
        val intent = opened.single()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data!!.scheme)
        assertTrue(intent.data!!.schemeSpecificPart.startsWith("yiddishdream@gmail.com"))
        assertFalse(intent.data.toString().contains("device"))
        assertEquals("YiDream", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun unavailableHandlerOffersCopyableContactDetailsWithoutOpeningAnotherService() {
        var attempts = 0
        AdminContactController(activity, contact) {
            attempts++
            throw ActivityNotFoundException()
        }.bind()
        activity.findViewById<Button>(R.id.contactWhatsappButton).performClick()
        assertEquals(1, attempts)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message.contains(AdminContactLinks.DISPLAY_PHONE))
        assertTrue(message.contains(AdminContactLinks.EMAIL))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("+33766790352", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(1, attempts)
    }

    @Test
    fun conditionsAreReadableInTheAppAndDoNotLaunchAPlaceholderWebsite() {
        val opened = mutableListOf<Intent>()
        val controller = AdminContactController(activity, contact) { opened += it }
        val information = LinearLayout(activity)
        controller.bindInformation(information)
        information.findViewById<Button>(R.id.contactUsageButton).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
        assertEquals(activity.getString(R.string.contact_usage_body), message)
        assertFalse(message.contains("placeholder", ignoreCase = true))
        assertTrue(opened.isEmpty())
    }
}
