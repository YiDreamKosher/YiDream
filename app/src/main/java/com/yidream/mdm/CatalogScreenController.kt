package com.yidream.mdm

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/** Catalog installation and update actions share the same verified APK installer. */
internal class CatalogScreenController(private val activity: AppCompatActivity, private val service: MdmService) {
    private val container get() = activity.findViewById<LinearLayout>(R.id.storeContent)
    private var generation = 0
    private var installing = false
    private var reload: (() -> Unit)? = null
    private val navy = Color.rgb(23, 36, 61)
    private val muted = Color.rgb(101, 115, 138)
    private val blue = Color.rgb(45, 104, 217)
    private fun text(id: Int) = activity.getString(id)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun label(parent: LinearLayout, value: String, size: Float = 14f, color: Int = muted): TextView = TextView(activity).also {
        it.text = value
        it.textSize = size
        it.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
        it.setTextColor(color)
        it.setLineSpacing(dp(3).toFloat(), 1f)
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun card(intro: Boolean = false): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(19), dp(20), dp(19), dp(19))
        setBackgroundResource(if (intro) R.drawable.store_intro else R.drawable.store_card)
        elevation = if (intro) 0f else dp(1).toFloat()
        container.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }

    private fun action(
        parent: LinearLayout,
        title: String,
        enabled: Boolean = true,
        secondary: Boolean = false,
        icon: Int = R.drawable.store_ic_download,
        run: (Button) -> Unit
    ): Button = Button(activity).also {
        val foreground = if (secondary) blue else Color.WHITE
        val colors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(141, 158, 187), foreground)
        )
        it.text = title
        it.textSize = 15f
        it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        it.isAllCaps = false
        it.isEnabled = enabled
        it.minHeight = dp(50)
        it.minimumHeight = dp(50)
        it.minWidth = 0
        it.setPadding(dp(22), dp(12), dp(22), dp(12))
        it.backgroundTintList = null
        it.setBackgroundResource(if (secondary) R.drawable.store_button_secondary else R.drawable.store_button_primary)
        it.setTextColor(colors)
        it.compoundDrawableTintList = colors
        it.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        it.compoundDrawablePadding = dp(10)
        it.stateListAnimator = null
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        it.setOnClickListener { _ -> run(it) }
    }

    private fun appHeading(parent: LinearLayout, app: JSONObject) {
        val pkg = app.getString("package_name")
        val name = app.getString("name")
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        val palette = when (pkg) {
            "com.whatsapp" -> Color.rgb(230, 246, 236) to Color.rgb(29, 140, 78)
            "tfilon.tfilon" -> Color.rgb(255, 245, 222) to Color.rgb(161, 116, 40)
            "com.waze" -> Color.rgb(225, 247, 252) to Color.rgb(17, 129, 157)
            "app.kosherscan.kosher_scan" -> Color.rgb(248, 235, 246) to Color.rgb(145, 81, 138)
            else -> Color.rgb(235, 241, 255) to blue
        }
        val avatar = FrameLayout(activity).apply {
            background = GradientDrawable().apply { setColor(palette.first); cornerRadius = dp(17).toFloat() }
            clipToOutline = true
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(14) })
        val installedIcon = runCatching { activity.packageManager.getApplicationIcon(pkg) }.getOrNull()
        if (installedIcon != null) {
            avatar.addView(ImageView(activity).apply {
                setImageDrawable(installedIcon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        } else {
            avatar.addView(TextView(activity).apply {
                this.text = name.trim().split(Regex("\\s+")).take(2).joinToString("") { it.take(1) }.uppercase(Locale.ROOT)
                textSize = 23f
                setTextColor(palette.second)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val heading = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        row.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        label(heading, name, 19f, navy).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(5)
        }
        if (app.optBoolean("is_base")) {
            label(heading, text(R.string.store_base_badge), 11f, blue).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply { setColor(Color.rgb(240, 245, 255)); cornerRadius = dp(20).toFloat() }
                setPadding(dp(9), dp(3), dp(9), dp(3))
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            }
        }
    }

    private fun installedVersion(pkg: String): Long? = try {
        @Suppress("DEPRECATION") val info = activity.packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun installedName(pkg: String, fallback: Long): String = try {
        @Suppress("DEPRECATION") val info = activity.packageManager.getPackageInfo(pkg, 0)
        info.versionName ?: fallback.toString()
    } catch (_: PackageManager.NameNotFoundException) { fallback.toString() }

    private fun ready(app: JSONObject) = CatalogUpdatePolicy.isReady(app.optString("apk_url"), app.optString("sha256"), app.optLong("version_code"))
    private fun needsUpdate(app: JSONObject) = app.getString("package_name") != activity.packageName && CatalogUpdatePolicy.shouldUpdate(installedVersion(app.getString("package_name")), app.optLong("version_code"))
    private fun baseMissing(app: JSONObject) = app.optBoolean("is_base") && installedVersion(app.getString("package_name")) == null && ready(app)

    fun load(onPair: () -> Unit) {
        reload = { load(onPair) }
        val request = ++generation
        container.removeAllViews()
        val intro = card(intro = true)
        label(intro, text(R.string.mdm_catalog_description), color = navy)
        if (!service.paired) {
            label(intro, text(R.string.mdm_pair_description))
            action(intro, text(R.string.mdm_pair), icon = R.drawable.store_ic_link) { onPair() }
            return
        }
        val owner = DeviceOwnerManager(activity).isDeviceOwner()
        if (!owner) label(intro, text(R.string.mdm_owner_required))
        val state = label(intro, text(R.string.mdm_loading))
        activity.lifecycleScope.launch {
            try {
                val response = service.catalog()
                if (request != generation) return@launch
                intro.removeView(state)
                val apps = (0 until response.length()).map { response.getJSONObject(it) }
                val updates = apps.filter { ready(it) && needsUpdate(it) }
                val missing = apps.filter { baseMissing(it) }
                if (updates.isNotEmpty()) action(intro, text(R.string.store_update_all) + " (" + updates.size + ")", owner && !installing) { b -> batch(updates, b) }
                if (missing.isNotEmpty()) action(intro, text(R.string.store_install_base) + " (" + missing.size + ")", owner && !installing) { b -> batch(missing, b) }
                if (apps.isEmpty()) label(intro, text(R.string.mdm_empty_catalog))
                else if (updates.isEmpty()) label(intro, text(R.string.store_no_published_updates))
                if (apps.any { GooglePlayUpdateSource.listing(it.optString("source_url"), it.optString("package_name")) != null }) {
                    label(intro, text(R.string.store_play_updates_title), 16f, navy).typeface = Typeface.DEFAULT_BOLD
                    label(intro, text(R.string.store_play_updates_description), 12f)
                }
                for (app in apps) renderAppCard(app, owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == generation) state.text = text(if (error.message == "SERVICE_NOT_READY") R.string.mdm_service_missing else R.string.mdm_network_error)
            }
            if (request == generation) action(card(), text(R.string.mdm_refresh), !installing, secondary = true, icon = R.drawable.store_ic_refresh) { load(onPair) }
        }
    }

    /** Renders the same catalog decisions independently of fetching the catalog. */
    internal fun renderAppCard(app: JSONObject, owner: Boolean) {
        val box = card()
        val pkg = app.getString("package_name")
        val installed = installedVersion(pkg)
        val available = app.optLong("version_code")
        appHeading(box, app)
        if (app.optString("description").isNotBlank()) label(box, app.optString("description"))
        val versionName = app.optString("version_name").takeUnless { it.isBlank() || it == "null" } ?: available.toString()
        label(box, if (installed == null) text(R.string.store_not_installed) else activity.getString(R.string.store_installed_version, installedName(pkg, installed)), 12f)
        if (available > 0L) label(box, activity.getString(R.string.store_available_version, versionName), 12f)
        when {
            !ready(app) -> {
                label(box, text(R.string.store_apk_missing), 13f)
                action(box, text(R.string.mdm_install), enabled = false) { }
            }
            installed == null -> action(box, text(R.string.mdm_install), owner && !installing) { b -> batch(listOf(app), b) }
            needsUpdate(app) -> action(box, text(R.string.store_update), owner && !installing) { b -> batch(listOf(app), b) }
            else -> {
                label(box, text(R.string.store_catalog_current), 12f, Color.rgb(29, 130, 88))
                val intent = activity.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) action(box, text(R.string.store_open), secondary = true, icon = R.drawable.store_ic_open) { activity.startActivity(intent) }
            }
        }
        GooglePlayUpdateSource.listing(app.optString("source_url"), pkg)?.let { listing ->
            val play = GooglePlayUpdates(activity)
            val availableOnDevice = play.available(listing)
            label(box, text(if (availableOnDevice) R.string.store_play_version_check else R.string.store_play_unavailable), 12f)
            action(box, text(R.string.store_check_play), availableOnDevice && !installing, secondary = true, icon = R.drawable.store_ic_refresh) {
                if (!play.open(listing)) Toast.makeText(activity, text(R.string.store_play_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun batch(apps: List<JSONObject>, button: Button) {
        if (installing) return
        installing = true
        button.isEnabled = false
        activity.lifecycleScope.launch {
            var succeeded = 0
            var failed = 0
            try {
                for ((index, app) in apps.withIndex()) {
                    button.text = activity.getString(R.string.store_install_progress, index + 1, apps.size, app.getString("name"))
                    val result = CatalogInstaller(activity).install(app)
                    if (result is SilentInstaller.Result.Success) succeeded++ else failed++
                }
                val summary = activity.getString(R.string.store_batch_result, succeeded, failed)
                button.text = summary
                Toast.makeText(activity, summary, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                button.text = text(R.string.mdm_install_failed)
            } finally {
                installing = false
                button.isEnabled = true
                if (currentCoroutineContext().isActive && !activity.isDestroyed && !activity.isFinishing) reload?.invoke()
            }
        }
    }
}
