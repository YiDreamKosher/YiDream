package com.yidream.mdm

import android.content.Intent
import android.content.pm.ResolveInfo
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.Collator

/** A local overview of the installed apps allowed by the last received device policy. */
internal class HomeOverviewController(private val activity: AppCompatActivity) {
    private val owner = DeviceOwnerManager(activity)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun refresh(policy: DevicePolicy?) {
        val target = activity.findViewById<LinearLayout>(R.id.homeAppsContent) ?: return
        target.removeAllViews()
        if (policy == null) {
            message(target, R.string.home_apps_waiting)
            return
        }
        val homePackages = activity.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }.toSet()
        val apps = activity.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).distinctBy { it.activityInfo.packageName }.filter { result ->
            val name = result.activityInfo.packageName
            name !in homePackages && permitted(name, policy)
        }.sortedWith(compareBy(Collator.getInstance()) { it.loadLabel(activity.packageManager).toString() })
        if (apps.isEmpty()) { message(target, R.string.home_apps_empty); return }
        val columns = if (activity.resources.configuration.screenWidthDp < 300) 1 else 2
        apps.chunked(columns).forEach { entries ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            target.addView(row, LinearLayout.LayoutParams(-1, -2))
            entries.forEach { result -> app(row, result) }
            if (entries.size < columns) row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    private fun permitted(name: String, policy: DevicePolicy): Boolean =
        name != activity.packageName && DevicePolicy.validPackageName(name) && policy.allowsApp(name) &&
            !owner.isInstallationChannel(name) && !owner.isAppBlocked(name)

    private fun app(row: LinearLayout, info: ResolveInfo) {
        val name = info.activityInfo.packageName
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(10), dp(12))
            minimumHeight = dp(64)
            setBackgroundResource(R.drawable.bg_quick_action)
            isClickable = true
            isFocusable = true
            contentDescription = info.loadLabel(activity.packageManager)
            setOnClickListener {
                val current = RemoteDevicePolicy.current(activity) ?: return@setOnClickListener
                if (!permitted(name, current)) return@setOnClickListener
                val launch = activity.packageManager.getLaunchIntentForPackage(name) ?: return@setOnClickListener
                runCatching { activity.startActivity(launch) }
            }
        }
        row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6); bottomMargin = dp(8) })
        tile.addView(ImageView(activity).apply {
            setImageDrawable(info.loadIcon(activity.packageManager))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(9) })
        tile.addView(TextView(activity).apply {
            text = info.loadLabel(activity.packageManager)
            textSize = 14f
            maxLines = 2
            setTextColor(activity.getColor(R.color.accent_navy))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun message(parent: LinearLayout, resource: Int) {
        parent.addView(TextView(activity).apply {
            setText(resource)
            textSize = 14f
            setTextColor(activity.getColor(R.color.text_secondary))
            setPadding(0, 0, 0, dp(12))
        })
    }
}
