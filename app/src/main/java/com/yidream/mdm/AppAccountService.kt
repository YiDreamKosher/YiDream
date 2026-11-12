package com.yidream.mdm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import java.time.Instant
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object AccountInput {
    fun administratorCode(value: String): String = value.trim().replace("-", "").replace(" ", "").uppercase(Locale.ROOT).also {
        require(it.matches(Regex("^[A-F0-9]{32}$"))) { "INVALID_ADMIN_CODE" }
    }
}

internal data class AppAccount(val id: String, val email: String, val phone: String, val emailVerified: Boolean)
internal data class AppAccess(
    val account: AppAccount,
    val deviceId: String?,
    val offline: Boolean = false,
    val offlineUntil: Long? = null
) {
    fun scope(): DeviceRequestScope? = deviceId?.let { DeviceRequestScope(account.id, it) }

    companion object {
        fun fromJson(value: JSONObject): AppAccess {
            val account = value.getJSONObject("account")
            check(account.optBoolean("is_active", false)) { "APP_ACCESS_DENIED" }
            return AppAccess(AppAccount(account.getString("id"), account.getString("email"),
                account.getString("contact_phone"), account.optBoolean("email_verified", false)),
                value.optString("device_id").takeIf { it.isNotBlank() && it != "null" })
        }
    }
}

internal interface AppAccountClient {
    val hasSession: Boolean
    suspend fun validateSession(): AppAccess
    suspend fun activateCode(code: String): AppAccess
    suspend fun logout()
}

/** Explicit dependency seam for isolated Android tests; production always uses the real client. */
internal object AppAccountDependencies {
    var factory: (Context) -> AppAccountClient = { AppAccountService(it) }
}

/** Only ciphertext and its random GCM IV are persisted; the AES key never leaves Android Keystore. */
internal class AppSessionStore(
    context: Context,
    requests: Boolean = false,
    private val testKey: (() -> SecretKey)? = null
) {
    private val preferences = context.getSharedPreferences(
        if (requests) "yidream_offline_requests" else "yidream_account_session", Context.MODE_PRIVATE
    )
    private val keyAlias = if (requests) "yidream.app.requests.aes.v1" else "yidream.app.account.aes.v1"
    private fun key(): SecretKey = testKey?.invoke() ?: synchronized(storageLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    fun read(): JSONObject? {
        // Use a single snapshot so a concurrent write cannot pair ciphertext with a different IV.
        val saved = preferences.all
        val encrypted = saved["ciphertext"] as? String ?: return null
        val iv = saved["iv"]
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128,
                Base64.decode(iv as String, Base64.NO_WRAP)))
            JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (_: Exception) {
            // A failed read of an old record must not erase a replacement session.
            synchronized(storageLock) {
                val current = preferences.all
                if (current["ciphertext"] == encrypted && current["iv"] == iv) clear()
            }
            null
        }
    }

    fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        synchronized(storageLock) {
            check(preferences.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "SESSION_STORAGE_FAILED" }
        }
    }

    fun clear() {
        synchronized(storageLock) { preferences.edit().clear().commit() }
    }

    companion object { private val storageLock = Any() }
}

internal class AccountRequestException(val status: Int, val reason: String) : Exception(reason)

internal class AppAccountService(private val context: Context) : AppAccountClient {
    private val store = AppSessionStore(context)
    override val hasSession: Boolean get() = store.read() != null

    override suspend fun activateCode(code: String): AppAccess {
        val normalized = AccountInput.administratorCode(code)
        // Once submitted, keep the credentials even if Android pauses the activity.
        // The server may already have consumed the one-time code.
        return withContext(NonCancellable) {
            sessionLock.withLock {
                val result = request("/rest/v1/rpc/yd_redeem_app_activation",
                    JSONObject().put("account_code", normalized))
                val access = AppAccess.fromJson(result)
                val appToken = result.getString("app_token")
                val deviceToken = result.getString("token")
                val expiresAt = result.getString("expires_at")
                check(access.deviceId != null && appToken.matches(TOKEN_FORMAT) &&
                    deviceToken.matches(TOKEN_FORMAT) && Instant.parse(expiresAt).isAfter(Instant.now())) {
                    "INVALID_SESSION"
                }
                val session = JSONObject().put("kind", "code").put("app_token", appToken)
                    .put("expires_at", expiresAt).put("device_id", access.deviceId).put("device_token", deviceToken)
                store.write(OfflineAccountLease.confirm(context, session, access))
                DeviceOwnerManager(context).closeManagedChrome()
                context.getSharedPreferences("yidream_device_portal", Context.MODE_PRIVATE).edit().clear().commit()
                access
            }
        }
    }

    override suspend fun validateSession(): AppAccess {
        if (!InternetReachability.hasInternetRoute(context)) {
            return offlineAccess() ?: error("OFFLINE_CONFIRMATION_REQUIRED")
        }
        return try {
            validateOnlineSession()
        } catch (error: Exception) {
            if (error !is ConnectException && error !is NoRouteToHostException &&
                error !is SocketTimeoutException && error !is UnknownHostException
            ) throw error
            offlineAccess() ?: throw error
        }
    }

    internal suspend fun validateOnlineSession(): AppAccess {
        check(InternetReachability.hasInternetRoute(context)) { "OFFLINE_CONFIRMATION_REQUIRED" }
        val session = validSession()
        return try {
            val deviceId = session.optString("device_id")
            val deviceToken = session.optString("device_token")
            if (deviceId.isBlank() || !deviceToken.matches(TOKEN_FORMAT)) {
                throw AccountRequestException(403, "APP_ACCESS_DENIED")
            }
            val result = request("/rest/v1/rpc/yd_app_access_status", JSONObject().put("app_token",
                if (session.optString("kind") == "code") session.getString("app_token") else JSONObject.NULL), headers(session))
            val access = AppAccess.fromJson(result)
            val codeSession = session.optString("kind") == "code"
            val expiry = if (codeSession) {
                check(access.deviceId == deviceId) { "SESSION_CHANGED" }
                val expiry = result.getString("expires_at")
                check(Instant.parse(expiry).isAfter(Instant.now())) { "INVALID_SESSION" }
                expiry
            } else {
                // Existing email sessions can continue only on their own previously approved phone.
                request("/rest/v1/rpc/yd_get_device_policy", JSONObject().put("device_token", deviceToken),
                    headers(session), maxResponseChars = 128 * 1024)
                null
            }
            val bound = access.copy(deviceId = deviceId)
            sessionLock.withLock {
                val current = store.read() ?: error("SIGN_IN_REQUIRED")
                check(matchesSession(current, session)) { "SESSION_CHANGED" }
                if (expiry != null) current.put("expires_at", expiry)
                store.write(OfflineAccountLease.confirm(context, current, bound))
            }
            bound
        } catch (error: Exception) {
            if ((error is AccountRequestException && (error.status == 401 || error.status == 403)) ||
                error.message?.contains("APP_ACCESS_DENIED") == true) {
                sessionLock.withLock {
                    if (store.read()?.let { matchesSession(it, session) } == true) clearLocal()
                }
            }
            throw error
        }
    }

    internal fun offlineAccess(): AppAccess? =
        if (DeviceOwnerManager(context).isDeviceOwner()) {
            store.read()?.let { OfflineAccountLease.read(context, it) }
        } else null

    internal suspend fun requestCredentials(scope: DeviceRequestScope): Pair<String, Map<String, String>> {
        val session = validSession()
        val lease = OfflineAccountLease.read(context, session)
        check(lease?.scope() == scope) { "SESSION_CHANGED" }
        return session.getString("device_token") to headers(session)
    }

    internal fun confirmQueueScope(scope: DeviceRequestScope): Boolean =
        store.read()?.let { OfflineAccountLease.read(context, it)?.scope() == scope } == true

    internal suspend fun invalidateQueueScope(scope: DeviceRequestScope) = sessionLock.withLock {
        if (confirmQueueScope(scope)) clearLocal()
    }

    private fun matchesSession(current: JSONObject, previous: JSONObject): Boolean {
        val credential = if (previous.optString("kind") == "code") "app_token" else "access_token"
        return current.optString(credential) == previous.optString(credential) &&
            current.optString("device_id") == previous.optString("device_id") &&
            current.optString("device_token") == previous.optString("device_token")
    }

    suspend fun requestHeaders(): Map<String, String> = headers(validSession())

    private suspend fun validSession(): JSONObject = sessionLock.withLock {
        var session = store.read() ?: error("SIGN_IN_REQUIRED")
        if (session.optString("kind") == "code") {
            val expires = runCatching { Instant.parse(session.getString("expires_at")).epochSecond }.getOrNull()
            if (expires == null || expires <= Instant.now().epochSecond) {
                clearLocal(); error("SESSION_EXPIRED")
            }
        } else if (session.optLong("expires_at", 0) <= Instant.now().epochSecond + 60) {
            try {
                val refreshed = request("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", session.getString("refresh_token")))
                requireSession(refreshed)
                refreshed.put("kind", "email")
                listOf("device_id", "device_token", "confirmed_access").forEach {
                    if (session.has(it)) refreshed.put(it, session.get(it))
                }
                store.write(refreshed); session = refreshed
            } catch (error: AccountRequestException) {
                if (error.status in listOf(400, 401, 403)) clearLocal()
                throw error
            }
        }
        session
    }

    override suspend fun logout() {
        val session = store.read()
        try {
            if (session != null) {
                if (session.optString("kind") == "code") request("/rest/v1/rpc/yd_logout_app_session",
                    JSONObject().put("app_token", session.getString("app_token")), headers(session))
                else request("/auth/v1/logout?scope=local", JSONObject(), headers(session))
            }
        } finally { withContext(NonCancellable) { sessionLock.withLock { clearLocal() } } }
    }

    private fun clearLocal() {
        store.clear()
        DeviceOwnerManager(context).closeManagedChrome()
        // A device credential is not a policy. Forgetting it never changes Device Owner or filtering.
        context.getSharedPreferences("yidream_device_portal", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun headers(session: JSONObject): Map<String, String> = if (session.optString("kind") == "code")
        mapOf("x-yidream-app-token" to session.getString("app_token"))
        else mapOf("Authorization" to "Bearer ${session.getString("access_token")}")

    private fun requireSession(value: JSONObject) {
        check(value.optString("access_token").isNotBlank() && value.optString("refresh_token").isNotBlank()) { "INVALID_SESSION" }
        if (!value.has("expires_at")) value.put("expires_at", Instant.now().epochSecond + value.getLong("expires_in"))
    }

    internal suspend fun saveDeviceCredential(value: JSONObject) = sessionLock.withLock {
        val session = store.read() ?: error("SIGN_IN_REQUIRED")
        session.remove("confirmed_access")
        session.put("device_id", value.getString("device_id")).put("device_token", value.getString("token"))
        store.write(session)
        // Remove credentials persisted by versions before mandatory account access.
        context.getSharedPreferences("yidream_device_portal", Context.MODE_PRIVATE).edit().clear().commit()
    }

    internal fun deviceCredential(): Pair<String, String>? = store.read()?.let { session ->
        val id = session.optString("device_id"); val token = session.optString("device_token")
        if (id.isNotBlank() && token.isNotBlank()) id to token else null
    }

    private suspend fun request(
        path: String, body: JSONObject, extraHeaders: Map<String, String> = emptyMap(),
        maxResponseChars: Int = 32 * 1024
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(SERVICE + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000; connection.readTimeout = 20000; connection.doOutput = true
            connection.setRequestProperty("apikey", PUBLIC_KEY)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 401 || status == 403) throw AccountRequestException(status, "APP_ACCESS_DENIED")
            val response = try {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(maxResponseChars + 1)
                    var length = 0
                    while (length < buffer.size) {
                        val count = reader.read(buffer, length, buffer.size - length)
                        if (count == -1) break
                        length += count
                    }
                    check(length <= maxResponseChars) { "RESPONSE_TOO_LARGE" }
                    String(buffer, 0, length)
                }.orEmpty()
            } catch (error: IOException) {
                if (status !in 200..299) throw AccountRequestException(status, "REQUEST_FAILED")
                throw error
            }
            if (status !in 200..299) {
                val error = runCatching { JSONObject(response) }.getOrNull()
                throw AccountRequestException(status, error?.optString("message")?.ifBlank { null }
                    ?: error?.optString("error_code")?.ifBlank { null } ?: "REQUEST_FAILED")
            }
            if (response.isBlank() || response == "null") JSONObject() else JSONObject(response)
        } finally { connection.disconnect() }
    }

    companion object {
        private val sessionLock = Mutex()
        private val TOKEN_FORMAT = Regex("^[a-fA-F0-9]{64}$")
        const val SERVICE = "https://jqrznghqwdjbizjehzrm.supabase.co"
        const val PUBLIC_KEY = "sb_publishable_YG9zgjQ5Y56WiBtKdJMQvA_-UBAFv9B"
        const val WHATSAPP_URL = "https://wa.me/33766790352"
    }
}
