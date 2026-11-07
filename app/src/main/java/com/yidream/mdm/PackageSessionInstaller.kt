package com.yidream.mdm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Used for both catalog apps and YiDream itself: a commit is not an install success. */
internal class PackageSessionInstaller(private val context: Context) {
    suspend fun install(file: File, expectedPackage: String, expectedVersion: Long): SilentInstaller.Result {
        currentCoroutineContext().ensureActive()
        val installer=context.packageManager.packageInstaller
        var sessionId=-1
        var submitted=false
        try {
            val metadata=ApkMetadata(context.packageManager)
            val candidate=metadata.archive(file.absolutePath)
                ?: return SilentInstaller.Result.Failure("Le fichier APK ne peut pas être lu par Android.")
            val rejection=ApkInstallPolicy.rejection(expectedPackage, expectedVersion, candidate, metadata.installed(expectedPackage))
            if(rejection!=null) return SilentInstaller.Result.Failure(when(rejection) {
                ApkInstallPolicy.Rejection.SIGNATURE_MISMATCH -> "La signature de cet APK est différente de celle de l’application installée. Une mise à jour nécessite la clé de signature d’origine. La configuration existante a été conservée."
                ApkInstallPolicy.Rejection.WRONG_PACKAGE -> "Cet APK ne correspond pas à l’application attendue."
                ApkInstallPolicy.Rejection.WRONG_VERSION -> "La version de l’APK ne correspond pas à la mise à jour annoncée."
                ApkInstallPolicy.Rejection.MISSING_SIGNATURE -> "Impossible de vérifier la signature de l’application."
                ApkInstallPolicy.Rejection.DOWNGRADE -> "Une version plus récente est déjà installée."
            })
            val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setInstallReason(PackageManager.INSTALL_REASON_POLICY)
            currentCoroutineContext().ensureActive()
            sessionId=installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("yidream_verified_apk",0,file.length()).use { output ->
                    file.inputStream().use { copyApkBytes(it, output) }
                    currentCoroutineContext().ensureActive()
                    session.fsync(output)
                }
                val callback=Intent(context,CatalogInstallResultReceiver::class.java)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID,sessionId)
                val pending=PendingIntent.getBroadcast(context,sessionId,callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                val completion=CompletableDeferred<SilentInstaller.Result>()
                // Once submitted, Android owns the transaction and may finish even if this
                // activity pauses. A cancelled download/copy must never reach that boundary.
                currentCoroutineContext().ensureActive()
                CatalogInstallSessions.pending[sessionId]=completion
                currentCoroutineContext().ensureActive()
                session.commit(pending.intentSender)
                submitted=true
                val result=withTimeoutOrNull(120_000L) { completion.await() }
                    ?: return SilentInstaller.Result.Failure("Android n’a pas confirmé l’installation. La mise à jour n’est pas considérée comme installée.")
                if(result is SilentInstaller.Result.Success && !ApkInstallPolicy.installationConfirmed(
                        expectedPackage, expectedVersion, metadata.installed(expectedPackage))) {
                    return SilentInstaller.Result.Failure("La version attendue n’est pas installée. Vérifiez la version dans les réglages et réessayez.")
                }
                return result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return SilentInstaller.Result.Failure(error.message ?: "Installation refusée par Android.") }
        finally {
            CatalogInstallSessions.pending.remove(sessionId)
            if(sessionId>=0&&!submitted)runCatching { installer.abandonSession(sessionId) }
        }
    }
}

