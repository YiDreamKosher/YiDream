package com.yidream.mdm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import java.net.URI
import java.net.URLDecoder

/** Only approved catalog entries may open their exact official Play listing. */
internal object GooglePlayUpdateSource {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun listing(source: String, expectedPackage: String): String? {
        return runCatching {
            if (!packageName.matches(expectedPackage)) return null
            val uri = URI(source)
            if (uri.scheme != "https" || uri.host != "play.google.com" || uri.rawUserInfo != null ||
                uri.port !in listOf(-1, 443) || uri.path != "/store/apps/details" || uri.rawFragment != null) return null
            val parameters = uri.rawQuery?.split('&')?.map { entry ->
                val pair = entry.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            } ?: return null
            val ids = parameters.filter { it.first == "id" }
            if (ids.size != 1 || ids[0].second != expectedPackage) return null
            "https://play.google.com/store/apps/details?id=$expectedPackage"
        }.getOrNull()
    }
}

/** Play manages its own version selection; YiDream does not infer a Play version from a link. */
internal class GooglePlayUpdates(private val context: Context) {
    fun intent(listing: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(listing)).setPackage(PLAY_PACKAGE)

    fun available(listing: String): Boolean {
        return runCatching {
            if (DeviceOwnerManager(context).isAppBlocked(PLAY_PACKAGE)) return false
            val users = context.getSystemService(Context.USER_SERVICE) as UserManager
            if (users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)) return false
            val info = intent(listing).resolveActivityInfo(context.packageManager, 0) ?: return false
            info.enabled && info.applicationInfo.enabled
        }.getOrDefault(false)
    }

    fun open(listing: String): Boolean {
        if (!available(listing)) return false
        return try {
            context.startActivity(intent(listing))
            true
        } catch (_: ActivityNotFoundException) { false }
          catch (_: SecurityException) { false }
    }

    companion object { const val PLAY_PACKAGE = "com.android.vending" }
}
