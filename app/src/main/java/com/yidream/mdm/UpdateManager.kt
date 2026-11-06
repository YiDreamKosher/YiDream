package com.yidream.mdm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates YiDream through its Device Owner installation session. Submission is
 * never considered success without Android's result and the installed version.
 *
 * The manifest is hosted with the public YiDream Pages site and includes
 * the checksum of the APK produced by the deployment workflow.
 */
class UpdateManager(private val context: Context) {

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkSha256: String,
        val changelog: String
    )

    sealed class CheckResult {
        data class Available(val update: UpdateInfo) : CheckResult()
        object UpToDate : CheckResult()
        object Unavailable : CheckResult()
    }

    companion object {
        const val MANIFEST_URL = "https://yidreamkosher.github.io/YiDream/version.json"
        const val APPROVED_APK_URL = "https://yidreamkosher.github.io/YiDream/admin/yidream.apk"

        internal fun evaluateManifest(body: String, currentVersionCode: Long): CheckResult {
            return try {
                val json = JSONObject(body)
                if (json.opt("versionCode") !is Int && json.opt("versionCode") !is Long)
                    return CheckResult.Unavailable
                val remoteVersion = json.getLong("versionCode")
                val apkUrl = json.getString("apkUrl")
                val sha = json.getString("apkSha256")
                if (remoteVersion !in 1..Int.MAX_VALUE.toLong() || apkUrl != APPROVED_APK_URL ||
                    !sha.matches(Regex("(?i)[0-9a-f]{64}"))) return CheckResult.Unavailable
                if (remoteVersion <= currentVersionCode) return CheckResult.UpToDate
                val name = json.getString("versionName").trim()
                if (name.isEmpty()) return CheckResult.Unavailable
                CheckResult.Available(UpdateInfo(remoteVersion.toInt(), name, apkUrl, sha,
                    json.optString("changelog", "")))
            } catch (_: Exception) { CheckResult.Unavailable }
        }
    }

    private val silentInstaller = SilentInstaller(context)

    suspend fun checkStatus(): CheckResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            if (connection.responseCode !in 200..299) {
                return@withContext CheckResult.Unavailable
            }
            val bytes = connection.inputStream.use { it.readBytesBounded(65536) }
            val body = bytes.toString(Charsets.UTF_8)
            val currentVersionCode = context.packageManager
                .getPackageInfo(context.packageName, 0).let {
                    if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode
                    else @Suppress("DEPRECATION") it.versionCode.toLong()
                }
            evaluateManifest(body, currentVersionCode)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CheckResult.Unavailable }
        finally { connection?.disconnect() }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val size = read(buffer)
            if (size < 0) return out.toByteArray()
            check(out.size() + size <= limit) { "Manifest too large" }
            out.write(buffer, 0, size)
        }
    }

    suspend fun installUpdate(update: UpdateInfo): SilentInstaller.Result {
        if (update.apkUrl != APPROVED_APK_URL) return SilentInstaller.Result.Failure("Source de mise à jour non autorisée.")
        return silentInstaller.downloadAndInstall(update.apkUrl, update.apkSha256, update.versionCode.toLong())
    }
}

