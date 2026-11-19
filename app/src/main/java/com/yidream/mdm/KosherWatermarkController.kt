package com.yidream.mdm

import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Appends an optional wallpaper control inside the signed-in appearance page. */
internal class KosherWatermarkController(private val activity: AppCompatActivity,
    private val gatewayFactory: () -> HomeWallpaperGateway = { SystemHomeWallpaperGateway(activity) }) {
    fun bind() {
        val parent = activity.findViewById<LinearLayout>(R.id.settingsPageAppearance) ?: return
        if (parent.findViewById<View>(R.id.kosherWatermarkCard) != null) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(activity).apply {
            id = R.id.kosherWatermarkCard
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_glass_card)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        }
        val title = TextView(activity).apply {
            setText(R.string.kosher_wallpaper_title)
            textSize = 16f
            setTextColor(activity.getColor(R.color.accent_navy))
            setTypeface(typeface, Typeface.BOLD)
        }
        val description = TextView(activity).apply {
            setText(R.string.kosher_wallpaper_summary)
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(10))
        }
        val state = TextView(activity).apply {
            id = R.id.kosherWatermarkStatus
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_secondary))
        }
        fun button(idValue: Int, label: Int) = AppCompatButton(activity).apply {
            id = idValue
            setText(label)
            isAllCaps = false
            textSize = 14f
            minHeight = dp(48)
            setTextColor(activity.getColor(R.color.accent_blue))
            setBackgroundResource(R.drawable.ui_secondary_button)
            backgroundTintList = null
            supportBackgroundTintList = null
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        val configure = button(R.id.kosherWatermarkConfigure, R.string.kosher_wallpaper_configure)
        configure.setOnClickListener {
            runCatching { activity.startActivity(Intent(activity, KosherWallpaperActivity::class.java)) }
                .onFailure { Toast.makeText(activity, R.string.kosher_wallpaper_unavailable, Toast.LENGTH_LONG).show() }
        }
        val remove = button(R.id.kosherWatermarkRemove, R.string.kosher_wallpaper_remove)
        remove.setOnClickListener {
            runCatching { activity.startActivity(wallpaperChooserIntent()) }
                .onFailure { Toast.makeText(activity, R.string.kosher_wallpaper_picker_unavailable, Toast.LENGTH_LONG).show() }
        }
        fun refresh() {
            // The wallpaper service is optional: unavailable vendor/framework implementations
            // disable this card without interrupting account or device management screens.
            val gateway = runCatching { gatewayFactory() }.getOrNull()
            val supported = gateway != null && runCatching { gateway.supported() }.getOrDefault(false)
            val active = supported && gateway != null && KosherWallpaperState(activity).isActive(gateway)
            configure.isEnabled = supported
            state.setText(when {
                !supported -> R.string.kosher_wallpaper_unavailable
                active -> R.string.kosher_wallpaper_active
                else -> R.string.kosher_wallpaper_inactive
            })
            remove.visibility = if (active) View.VISIBLE else View.GONE
        }
        card.addView(title)
        card.addView(description)
        card.addView(state)
        card.addView(configure)
        card.addView(remove)
        parent.addView(card)
        refresh()
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (activity.findViewById<View>(R.id.kosherWatermarkCard) === card) refresh()
                else activity.lifecycle.removeObserver(this)
            }
        })
    }
}
