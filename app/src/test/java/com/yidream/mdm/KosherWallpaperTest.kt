package com.yidream.mdm

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class KosherWallpaperTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private class Gateway(var supportedValue: Boolean = true, var id: Int = 0) : HomeWallpaperGateway {
        var appliedFlag: Int? = null
        var calls = 0
        override fun supported() = supportedValue
        override fun currentId() = id
        override fun set(bitmap: Bitmap, which: Int): Int {
            assertFalse(bitmap.isRecycled)
            appliedFlag = which
            calls++
            id = 19
            return id
        }
    }

    @Test fun applyingWritesOnlyTheHomeWallpaperAndTracksTheRealWallpaperId() {
        val state = KosherWallpaperState(context)
        val gateway = Gateway()
        val bitmap = KosherWallpaper.render(240, 320)
        try {
            assertFalse(state.isActive(gateway))
            KosherWallpaperInstaller(gateway, state).apply(bitmap)
            assertEquals(WallpaperManager.FLAG_SYSTEM, gateway.appliedFlag)
            assertEquals(19, state.appliedId)
            assertTrue(state.isActive(gateway))
            gateway.id = 20
            assertFalse(state.isActive(gateway))
        } finally { bitmap.recycle() }
    }

    @Test fun unsupportedPhonesKeepTheCurrentWallpaperAndInactiveState() {
        val gateway = Gateway(supportedValue = false)
        val state = KosherWallpaperState(context)
        val bitmap = KosherWallpaper.render(240, 320)
        try {
            assertThrows(IOException::class.java) { KosherWallpaperInstaller(gateway, state).apply(bitmap) }
            assertEquals(0, gateway.calls)
            assertEquals(0, state.appliedId)
            assertFalse(state.isActive(gateway))
        } finally { bitmap.recycle() }
    }

    @Test fun wallpaperRenderingBoundsMemoryAndSupportsSmallPhones() {
        val huge = KosherWallpaper.boundedSize(20_000, 30_000)
        assertTrue(huge.first.toLong() * huge.second <= 2_073_600)
        val background = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val small = KosherWallpaper.render(240, 320, background)
        try {
            assertEquals(240, small.width)
            assertEquals(320, small.height)
            assertFalse(background.isRecycled)
        } finally { small.recycle(); background.recycle() }
    }

    @Test fun removalUsesTheNativeChooserInsteadOfClearingAnotherWallpaper() {
        assertEquals(Intent.ACTION_SET_WALLPAPER, wallpaperChooserIntent().action)
    }

    @Test fun appearanceControlOpensAnInternalPreviewAndDoesNotApplyOnBind() {
        val controller = Robolectric.buildActivity(WatermarkSettingsHost::class.java).setup()
        try {
            val activity = controller.get()
            val gateway = Gateway()
            val watermark = KosherWatermarkController(activity) { gateway }
            watermark.bind()
            watermark.bind()
            val container = activity.findViewById<LinearLayout>(R.id.settingsPageAppearance)
            assertEquals(1, container.childCount)
            assertEquals(0, gateway.calls)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.kosherWatermarkRemove).visibility)
            activity.findViewById<View>(R.id.kosherWatermarkConfigure).performClick()
            assertEquals(ComponentName(activity, KosherWallpaperActivity::class.java),
                shadowOf(activity).nextStartedActivity.component)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun creatingTheSystemGatewayDoesNotAcquireTheWallpaperService() {
        // This must also work when the optional framework service cannot initialize.
        SystemHomeWallpaperGateway(context)
    }

    @Test fun unavailableWallpaperServiceDoesNotInterruptTheSettingsHost() {
        val controller = Robolectric.buildActivity(WatermarkSettingsHost::class.java).setup()
        try {
            val activity = controller.get()
            val gateway = object : HomeWallpaperGateway {
                override fun supported(): Boolean = throw NoClassDefFoundError("Unavailable wallpaper service")
                override fun currentId(): Int = error("Unavailable services must not be read")
                override fun set(bitmap: Bitmap, which: Int): Int = error("No wallpaper was requested")
            }
            KosherWatermarkController(activity) { gateway }.bind()
            assertWallpaperUnavailable(activity)
            controller.pause().resume()
            assertWallpaperUnavailable(activity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unavailableWallpaperProviderDoesNotInterruptTheSettingsHost() {
        val controller = Robolectric.buildActivity(WatermarkSettingsHost::class.java).setup()
        try {
            val activity = controller.get()
            KosherWatermarkController(activity) { throw IllegalStateException("No wallpaper provider") }.bind()
            assertWallpaperUnavailable(activity)
        } finally { controller.pause().stop().destroy() }
    }

    private fun assertWallpaperUnavailable(activity: AppCompatActivity) {
        assertFalse(activity.findViewById<View>(R.id.kosherWatermarkConfigure).isEnabled)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.kosherWatermarkRemove).visibility)
        assertEquals(activity.getString(R.string.kosher_wallpaper_unavailable),
            activity.findViewById<TextView>(R.id.kosherWatermarkStatus).text.toString())
    }

    @Test fun previewIsPrivateAndNoOverlayOrUsagePermissionIsAdded() {
        val packages = context.packageManager
        val activity = packages.getActivityInfo(ComponentName(context, KosherWallpaperActivity::class.java), 0)
        assertFalse(activity.exported)
        @Suppress("DEPRECATION")
        val permissions = packages.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.SET_WALLPAPER" in permissions)
        assertFalse("android.permission.SYSTEM_ALERT_WINDOW" in permissions)
        assertFalse("android.permission.PACKAGE_USAGE_STATS" in permissions)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
    }
}

class WatermarkSettingsHost : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_YiDream)
        super.onCreate(savedInstanceState)
        setContentView(LinearLayout(this).apply { id = R.id.settingsPageAppearance })
    }
}
