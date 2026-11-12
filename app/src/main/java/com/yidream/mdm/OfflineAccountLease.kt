package com.yidream.mdm

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.time.Instant

/** Offline access is a time-limited copy of a confirmed server binding in the encrypted session. */
internal object OfflineAccountLease {
    private const val KEY = "confirmed_access"
    private const val MAX_AGE = 7L * 24 * 60 * 60 * 1000

    internal data class Clock(val wall: Long, val elapsed: Long, val boot: Int)

    private fun clock(context: Context) = Clock(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1))

    fun confirm(context: Context, session: JSONObject, access: AppAccess): JSONObject =
        confirm(session, access, clock(context))

    internal fun confirm(session: JSONObject, access: AppAccess, time: Clock): JSONObject {
        require(!access.offline && access.deviceId != null && access.deviceId == session.optString("device_id"))
        val expiration = sessionExpiry(session)
        check(expiration > time.wall) { "SESSION_EXPIRED" }
        val account = JSONObject().put("id", access.account.id).put("email", access.account.email)
            .put("contact_phone", access.account.phone).put("email_verified", access.account.emailVerified)
            .put("is_active", true)
        return session.put(KEY, JSONObject().put("account", account).put("device_id", access.deviceId)
            .put("checked_at", time.wall).put("checked_elapsed", time.elapsed).put("boot", time.boot)
            .put("valid_until", minOf(expiration, time.wall + MAX_AGE)))
    }

    fun read(context: Context, session: JSONObject): AppAccess? = read(session, clock(context))

    internal fun read(session: JSONObject, time: Clock): AppAccess? {
        return runCatching {
            val lease = session.getJSONObject(KEY)
            val issued = lease.getLong("checked_at")
            val expires = minOf(lease.getLong("valid_until"), sessionExpiry(session), issued + MAX_AGE)
            if (time.wall < issued || time.wall >= expires) return null
            var remaining = expires - time.wall
            if (time.boot >= 0 && time.boot == lease.optInt("boot", -1)) {
                val elapsed = time.elapsed - lease.getLong("checked_elapsed")
                if (elapsed < 0 || elapsed >= expires - issued) return null
                remaining = minOf(remaining, expires - issued - elapsed)
            }
            val access = AppAccess.fromJson(lease)
            if (access.deviceId.isNullOrBlank() || access.deviceId != session.optString("device_id") ||
                access.account.id.isBlank() || session.optString("device_token").isBlank()) return null
            access.copy(offline = true, offlineUntil = time.wall + remaining)
        }.getOrNull()
    }

    private fun sessionExpiry(session: JSONObject): Long = if (session.optString("kind") == "code") {
        Instant.parse(session.getString("expires_at")).toEpochMilli()
    } else {
        Instant.ofEpochSecond(session.getLong("expires_at")).toEpochMilli()
    }
}
