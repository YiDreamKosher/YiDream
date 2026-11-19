package com.yidream.mdm

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

/** Explicit preview before replacing the home wallpaper; it never reads the current one. */
class KosherWallpaperActivity : AppCompatActivity() {
    private lateinit var preview: KosherWallpaperPreview
    private var image: Bitmap? = null
    private var busy = false
    private val account by lazy { AppAccountDependencies.factory(this) }
    private val backgroundFile get() = File(filesDir, "kosher_wallpaper_background.png")
    private val chooseImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) loadImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!signedIn()) { returnToAccount(); return }
        setContentView(R.layout.activity_kosher_wallpaper)
        preview = findViewById(R.id.kosherWallpaperPreview)
        findViewById<View>(R.id.kosherWallpaperBack).setOnClickListener { finish() }
        findViewById<View>(R.id.kosherWallpaperChooseImage).setOnClickListener {
            runCatching { chooseImage.launch("image/*") }.onFailure { showError(R.string.kosher_wallpaper_image_unavailable) }
        }
        findViewById<View>(R.id.kosherWallpaperDefault).setOnClickListener {
            if (!busy) {
                backgroundFile.delete()
                replaceImage(null)
            }
        }
        findViewById<View>(R.id.kosherWallpaperApply).setOnClickListener { apply() }
        if (backgroundFile.isFile) {
            setBusy(true)
            lifecycleScope.launch {
                val restored = withContext(Dispatchers.IO) { runCatching { decode { backgroundFile.inputStream() } }.getOrNull() }
                if (!isFinishing) replaceImage(restored) else restored?.recycle()
                setBusy(false)
            }
        }
    }

    private fun loadImage(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = decode { requireNotNull(contentResolver.openInputStream(uri)) }
                    try {
                        backgroundFile.outputStream().use { output ->
                            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException()
                        }
                        bitmap
                    } catch (error: Exception) { bitmap.recycle(); throw error }
                }
            }
            selected.onSuccess { replaceImage(it) }.onFailure { showError(R.string.kosher_wallpaper_image_unavailable) }
            setBusy(false)
        }
    }

    private fun decode(open: () -> java.io.InputStream): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth in 1..100_000 && bounds.outHeight in 1..100_000)
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 1600) sample *= 2
        return open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IOException("IMAGE_UNAVAILABLE")
        }
    }

    private fun apply() {
        if (busy) return
        setBusy(true)
        // A detached copy avoids racing the preview bitmap when the activity closes.
        val source = image?.copy(Bitmap.Config.ARGB_8888, false)
        lifecycleScope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val wallpaper = WallpaperManager.getInstance(this@KosherWallpaperActivity)
                        val display = resources.displayMetrics
                        val width = wallpaper.desiredMinimumWidth.takeIf { it > 0 } ?: display.widthPixels
                        val height = wallpaper.desiredMinimumHeight.takeIf { it > 0 } ?: display.heightPixels
                        val bitmap = KosherWallpaper.render(width, height, source)
                        try {
                            KosherWallpaperInstaller(SystemHomeWallpaperGateway(this@KosherWallpaperActivity),
                                KosherWallpaperState(this@KosherWallpaperActivity)).apply(bitmap)
                        } finally { bitmap.recycle() }
                    }.isSuccess
                }
            } finally { source?.recycle() }
            if (success) {
                Toast.makeText(this@KosherWallpaperActivity, R.string.kosher_wallpaper_applied, Toast.LENGTH_LONG).show()
                finish()
            } else {
                showError(R.string.kosher_wallpaper_apply_failed)
                setBusy(false)
            }
        }
    }

    private fun replaceImage(value: Bitmap?) {
        val old = image
        image = value
        preview.backgroundImage = value
        if (old !== value) old?.recycle()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        listOf(R.id.kosherWallpaperChooseImage, R.id.kosherWallpaperDefault, R.id.kosherWallpaperApply)
            .forEach { findViewById<View>(it).isEnabled = !value }
        findViewById<TextView>(R.id.kosherWallpaperStatus).setText(if (value) R.string.kosher_wallpaper_wait else R.string.kosher_wallpaper_home_only)
    }

    private fun showError(message: Int) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onResume() {
        super.onResume()
        if (!signedIn()) returnToAccount()
    }

    private fun signedIn() = runCatching { account.hasSession }.getOrDefault(false)

    private fun returnToAccount() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        if (::preview.isInitialized) preview.backgroundImage = null
        image?.recycle()
        image = null
        super.onDestroy()
    }
}
