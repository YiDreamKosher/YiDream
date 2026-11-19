package com.yidream.mdm

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A home wallpaper, never a window drawn over another application. */
internal object KosherWallpaper {
    private const val MAX_PIXELS = 2_073_600

    fun boundedSize(width: Int, height: Int): Pair<Int, Int> {
        val w = width.coerceIn(64, 4096)
        val h = height.coerceIn(64, 4096)
        val scale = min(1.0, sqrt(MAX_PIXELS.toDouble() / (w.toDouble() * h)))
        return max(64, (w * scale).toInt()) to max(64, (h * scale).toInt())
    }

    fun render(width: Int, height: Int, background: Bitmap? = null): Bitmap {
        val (w, h) = boundedSize(width, height)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            draw(Canvas(it), w, h, background)
        }
    }

    fun draw(canvas: Canvas, width: Int, height: Int, background: Bitmap?) {
        if (width <= 0 || height <= 0) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (background != null && !background.isRecycled) {
            val scale = max(width.toFloat() / background.width, height.toFloat() / background.height)
            val targetWidth = background.width * scale
            val targetHeight = background.height * scale
            val left = (width - targetWidth) / 2f
            val top = (height - targetHeight) / 2f
            canvas.drawBitmap(background, null, RectF(left, top, left + targetWidth, top + targetHeight), paint)
        } else {
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                Color.rgb(246, 243, 252), Color.rgb(216, 225, 243), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
        val bright = background == null || runCatching {
            val pixel = background.getPixel(background.width / 2, background.height * 4 / 5)
            Color.red(pixel) * .299 + Color.green(pixel) * .587 + Color.blue(pixel) * .114 > 140
        }.getOrDefault(true)
        paint.color = if (bright) Color.argb(165, 38, 41, 71) else Color.argb(210, 255, 255, 255)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = width * .085f
        canvas.drawText("Kosher", width / 2f, height * .85f, paint)
    }
}

internal interface HomeWallpaperGateway {
    fun supported(): Boolean
    fun currentId(): Int
    fun set(bitmap: Bitmap, which: Int): Int
}

internal class SystemHomeWallpaperGateway(context: Context) : HomeWallpaperGateway {
    // Looking up this optional system service must never be part of constructing the workspace.
    private val wallpaper by lazy { WallpaperManager.getInstance(context.applicationContext) }
    override fun supported() = wallpaper.isWallpaperSupported && wallpaper.isSetWallpaperAllowed
    override fun currentId() = wallpaper.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    override fun set(bitmap: Bitmap, which: Int) = wallpaper.setBitmap(bitmap, null, false, which)
}

internal class KosherWallpaperState(context: Context) {
    private val preferences = context.getSharedPreferences("kosher_home_wallpaper", Context.MODE_PRIVATE)
    var appliedId: Int
        get() = preferences.getInt("wallpaper_id", 0)
        set(value) { preferences.edit().putInt("wallpaper_id", value).apply() }

    fun isActive(gateway: HomeWallpaperGateway): Boolean = appliedId.takeIf { it > 0 }?.let { id ->
        runCatching { gateway.currentId() == id }.getOrDefault(false)
    } ?: false
}

internal class KosherWallpaperInstaller(private val gateway: HomeWallpaperGateway,
    private val state: KosherWallpaperState) {
    fun apply(bitmap: Bitmap) {
        if (!gateway.supported()) throw IOException("WALLPAPER_UNAVAILABLE")
        // Never write FLAG_LOCK, clear a wallpaper, or inspect the user's existing image.
        val id = gateway.set(bitmap, WallpaperManager.FLAG_SYSTEM)
        if (id <= 0) throw IOException("WALLPAPER_NOT_APPLIED")
        state.appliedId = id
    }
}

internal fun wallpaperChooserIntent() = Intent(Intent.ACTION_SET_WALLPAPER)

class KosherWallpaperPreview @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var backgroundImage: Bitmap? = null
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        KosherWallpaper.draw(canvas, width, height, backgroundImage)
    }
}
