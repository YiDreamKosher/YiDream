package com.yidream.mdm

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** Installs a published release only when the current device policy authorizes it. */
internal class CatalogInstaller(private val context: Context) {
    private data class Release(val packageName: String, val version: Long, val url: String, val sha256: String) {
        companion object {
            fun from(app: JSONObject) = Release(
                app.optString("package_name"), app.optLong("version_code"),
                app.optString("apk_url"), app.optString("sha256").lowercase(Locale.ROOT)
            )
        }
    }

    suspend fun install(app: JSONObject): SilentInstaller.Result = withContext(Dispatchers.IO) {
        val owner = DeviceOwnerManager(context)
        val service = MdmService(context)
        val device = service.deviceCredential()
            ?: return@withContext SilentInstaller.Result.Failure("Appareil non associé.")
        val release = Release.from(app)
        if (release.packageName == context.packageName || !DevicePolicy.validPackageName(release.packageName) ||
            !CatalogUpdatePolicy.isReady(release.url, release.sha256, release.version)) {
            return@withContext SilentInstaller.Result.Failure("Mise à jour invalide.")
        }
        val metadata = ApkMetadata(context.packageManager)
        val firstInstall = metadata.installed(release.packageName) == null
        suspend fun authorized(): Boolean {
            if (!owner.isDeviceOwner() || owner.isInstallationChannel(release.packageName) || service.deviceCredential() != device) return false
            val policy = service.devicePolicy(device)
            if (!CatalogUpdatePolicy.canInstall(policy, release.packageName,
                    metadata.installed(release.packageName)?.versionCode, release.version, firstInstall)) return false
            val entries = service.catalog()
            val listed = (0 until entries.length()).any { index ->
                entries.optJSONObject(index)?.let { Release.from(it) == release } == true
            }
            return listed && service.deviceCredential() == device && !owner.isInstallationChannel(release.packageName) &&
                !owner.dpm.isApplicationHidden(owner.adminComponent, release.packageName)
        }

        var temporaryApk: File? = null
        var connection: HttpURLConnection? = null
        try {
            if (!authorized()) return@withContext SilentInstaller.Result.Failure("Cette mise à jour n’est pas autorisée.")
            val apk = File.createTempFile("yidream_catalog_", ".apk", context.cacheDir)
            temporaryApk = apk
            var downloadUrl = URL(release.url)
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                require(downloadUrl.protocol == "https" && downloadUrl.host.isNotBlank() &&
                    downloadUrl.userInfo == null) { "Source HTTPS requise." }
                val opened = downloadUrl.openConnection() as HttpURLConnection
                connection = opened
                service.authorizeDownload(opened, downloadUrl)
                opened.instanceFollowRedirects = false
                opened.connectTimeout = 15000
                opened.readTimeout = 20000
                val response = opened.responseCode
                if (response in listOf(301, 302, 303, 307, 308)) {
                    val location = opened.getHeaderField("Location") ?: error("Redirection invalide.")
                    require(++redirects <= 5) { "Trop de redirections." }
                    downloadUrl = URL(downloadUrl, location)
                    opened.disconnect()
                } else {
                    require(response in 200..299) { "Téléchargement refusé (HTTP $response)." }
                    break
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            requireNotNull(connection).inputStream.use { input ->
                apk.outputStream().use { output -> copyApkBytes(input, output, digest = digest) }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (actualSha256 != release.sha256) {
                return@withContext SilentInstaller.Result.Failure("La vérification de l’APK a échoué.")
            }
            PackageSessionInstaller(context).install(apk, release.packageName, release.version, firstInstall) {
                authorized()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SilentInstaller.Result.Failure("Impossible de vérifier ou d’appliquer cette mise à jour.")
        } finally {
            connection?.disconnect()
            temporaryApk?.delete()
        }
    }
}
