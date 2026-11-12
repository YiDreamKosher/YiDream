package com.yidream.mdm

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class YiDreamTileService : TileService() {
    override fun onTileAdded() {
        QuickSettingsLaunch(this).rememberTile(true)
    }

    override fun onTileRemoved() {
        QuickSettingsLaunch(this).rememberTile(false)
    }

    override fun onStartListening() {
        super.onStartListening()
        QuickSettingsLaunch(this).rememberTile(true)
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}

internal class QuickSettingsLaunch(private val context: Context) {
    private val launcher = ComponentName(context.packageName, "${context.packageName}.YiDreamLauncher")
    private val preferences = context.getSharedPreferences("yidream_quick_settings", Context.MODE_PRIVATE)
    private val packages = context.packageManager

    val launcherHidden: Boolean
        get() = packages.getComponentEnabledSetting(launcher) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun rememberTile(present: Boolean) {
        if (!present) restoreLauncher()
        preferences.edit().putBoolean("tile_present", present).commit()
    }

    fun restoreLauncher() {
        packages.setComponentEnabledSetting(launcher, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP)
    }

    internal fun hideWhenReady(): Boolean {
        if (!preferences.getBoolean("tile_present", false)) return false
        return runCatching {
            packages.setComponentEnabledSetting(launcher, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
            launcherHidden
        }.getOrDefault(false)
    }

    fun requestHide(activity: Activity, finished: (Boolean) -> Unit) {
        if (hideWhenReady()) {
            finished(true)
            return
        }
        if (Build.VERSION.SDK_INT < 33) {
            finished(false)
            return
        }
        val status = activity.getSystemService(StatusBarManager::class.java)
        if (status == null) {
            finished(false)
            return
        }
        try {
            status.requestAddTileService(ComponentName(activity, YiDreamTileService::class.java),
                activity.getString(R.string.app_name), Icon.createWithResource(activity, R.drawable.ic_shield),
                activity.mainExecutor) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                if (added) rememberTile(true)
                finished(added && hideWhenReady())
            }
        } catch (_: RuntimeException) {
            finished(false)
        }
    }
}
