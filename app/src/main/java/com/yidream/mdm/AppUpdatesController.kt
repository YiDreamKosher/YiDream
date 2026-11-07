package com.yidream.mdm

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Production and tests share the same screen; only the I/O boundary is replaceable. */
internal interface AppUpdateClient {
    suspend fun check(): UpdateManager.CheckResult
    suspend fun install(update: UpdateManager.UpdateInfo): SilentInstaller.Result
}

private class VerifiedAppUpdateClient(context: Context) : AppUpdateClient {
    private val manager = UpdateManager(context)
    override suspend fun check() = manager.checkStatus()
    override suspend fun install(update: UpdateManager.UpdateInfo) = manager.installUpdate(update)
}

/** Isolated view tests replace I/O here; the default always uses the verified updater. */
internal object AppUpdatesDependencies {
    var factory: (Context) -> AppUpdateClient = { VerifiedAppUpdateClient(it) }
    fun reset() { factory = { VerifiedAppUpdateClient(it) } }
}

/** Checks are optional; installation always requires a fresh, explicit button press. */
internal class AppUpdatesController(
    private val activity: AppCompatActivity,
    private val client: AppUpdateClient = AppUpdatesDependencies.factory(activity),
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val preferences = activity.getSharedPreferences("yidream_optional_updates", Context.MODE_PRIVATE)
    private var parent: LinearLayout? = null
    private var result: UpdateManager.CheckResult? = null
    private var checking = false
    private var installing = false
    private var installResult: SilentInstaller.Result? = null
    private var job: Job? = null
    private var availability: ((Boolean) -> Unit)? = null
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun mount(container: LinearLayout) {
        parent = container
        render()
        if (!checking && !installing) check(showProgress = true)
    }

    /** Called only after account authorization. A network failure never opens a dialog. */
    fun checkAvailability(onAvailable: (Boolean) -> Unit) {
        availability = onAvailable
        val checked = preferences.getLong("last_checked_at", 0)
        val age = nowMillis() - checked
        if (checked > 0 && age in 0 until BACKGROUND_INTERVAL_MS) {
            onAvailable(preferences.getLong("available_version", 0) > installedVersion() &&
                preferences.getLong("available_version", 0) != preferences.getLong("deferred_version", 0))
            return
        }
        if (!checking && !installing) check(showProgress = false)
    }

    fun cancel() {
        job?.cancel()
        job = null
        checking = false
        installing = false
        parent = null
        availability = null
    }

    private fun installedVersion(): Long = activity.packageManager.getPackageInfo(activity.packageName, 0).let {
        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode
        else @Suppress("DEPRECATION") it.versionCode.toLong()
    }

    private fun check(showProgress: Boolean) {
        if (checking || installing) return
        checking = true
        installResult = null
        if (showProgress) render()
        job = activity.lifecycleScope.launch {
            try {
                result = client.check()
                val update = (result as? UpdateManager.CheckResult.Available)?.update
                preferences.edit().putLong("last_checked_at", nowMillis())
                    .putLong("available_version", update?.versionCode?.toLong() ?: 0L).apply()
                availability?.invoke(update != null && update.versionCode.toLong() != preferences.getLong("deferred_version", 0))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { result = UpdateManager.CheckResult.Unavailable }
            finally {
                checking = false
                render()
            }
        }
    }

    private fun install(update: UpdateManager.UpdateInfo) {
        if (installing || checking) return
        installing = true
        installResult = null
        render()
        job = activity.lifecycleScope.launch {
            try {
                // UpdateManager and PackageSessionInstaller verify the fixed HTTPS source,
                // checksum, package, version, signing certificate and Android's final result.
                installResult = client.install(update)
                if (installResult is SilentInstaller.Result.Success) {
                    result = UpdateManager.CheckResult.UpToDate
                    preferences.edit().remove("available_version").remove("deferred_version").apply()
                    availability?.invoke(false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { installResult = SilentInstaller.Result.Failure(activity.getString(R.string.app_update_failed)) }
            finally {
                installing = false
                render()
            }
        }
    }

    private fun later(update: UpdateManager.UpdateInfo) {
        preferences.edit().putLong("deferred_version", update.versionCode.toLong()).apply()
        availability?.invoke(false)
        render()
    }

    private fun render() {
        val container = parent ?: return
        container.removeAllViews()
        val update = (result as? UpdateManager.CheckResult.Available)?.update
        val deferred = update != null && preferences.getLong("deferred_version", 0) == update.versionCode.toLong()
        val state = when {
            installing -> activity.getString(R.string.app_update_installing)
            checking || result == null -> activity.getString(R.string.app_update_checking)
            installResult is SilentInstaller.Result.Success -> activity.getString(R.string.app_update_success)
            installResult is SilentInstaller.Result.Failure -> activity.getString(R.string.app_update_failed)
            update != null && deferred -> activity.getString(R.string.app_update_deferred, update.versionName)
            update != null -> activity.getString(R.string.app_update_available, update.versionName)
            result is UpdateManager.CheckResult.UpToDate -> activity.getString(R.string.app_update_current)
            else -> activity.getString(R.string.app_update_unavailable)
        }
        paragraph(container, state, R.id.appUpdateState, heading = true)
        if (checking || installing) {
            container.addView(ProgressBar(activity).apply { id = R.id.appUpdateProgress },
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(14); bottomMargin = dp(14) })
        }
        if (update != null && update.changelog.isNotBlank()) paragraph(container, update.changelog, R.id.appUpdateChangelog)
        val failure = installResult as? SilentInstaller.Result.Failure
        if (failure != null && failure.message.isNotBlank()) paragraph(container, failure.message)
        paragraph(container, activity.getString(R.string.app_update_optional_note))
        if (checking || installing) return
        if (update != null) {
            action(container, R.id.appUpdateNow, R.string.app_update_now, primary = true) { install(update) }
            if (!deferred) action(container, R.id.appUpdateLater, R.string.app_update_later) { later(update) }
        } else {
            action(container, R.id.appUpdateCheck, R.string.app_update_check_again, primary = true) { check(showProgress = true) }
        }
    }

    private fun paragraph(container: LinearLayout, value: String, id: Int = View.NO_ID, heading: Boolean = false) {
        container.addView(TextView(activity).apply {
            this.id = id
            text = value
            textSize = if (heading) 18f else 13f
            setTextColor(activity.getColor(if (heading) R.color.accent_navy else R.color.text_secondary))
            setLineSpacing(dp(3).toFloat(), 1f)
            if (heading) setTypeface(typeface, Typeface.BOLD)
            if (id == R.id.appUpdateState) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    private fun action(container: LinearLayout, id: Int, label: Int, primary: Boolean = false, clicked: () -> Unit) {
        container.addView(Button(activity).apply {
            this.id = id
            setText(label)
            isAllCaps = false
            textSize = 14f
            minHeight = dp(52)
            minimumHeight = dp(52)
            minWidth = 0
            minimumWidth = 0
            backgroundTintList = null
            stateListAnimator = null
            setBackgroundResource(if (primary) R.drawable.ui_primary_button else R.drawable.ui_secondary_button)
            setTextColor(activity.getColor(if (primary) R.color.white else R.color.accent_blue))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { clicked() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    companion object { private const val BACKGROUND_INTERVAL_MS = 6L * 60L * 60L * 1000L }
}
