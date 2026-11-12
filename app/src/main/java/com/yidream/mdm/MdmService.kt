package com.yidream.mdm

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** Device credentials are scoped to one device; administrator sessions never enter the APK. */
class MdmService(context: Context) {
    private val account = AppAccountService(context)
    val paired: Boolean get() = account.deviceCredential() != null
    internal fun deviceCredential(): Pair<String, String>? = account.deviceCredential()

    suspend fun pair(code: String) {
        val result = rpc("yd_pair_device", JSONObject().put("pairing_code", code.trim())).getJSONObject(0)
        account.saveDeviceCredential(result)
    }

    suspend fun catalog(): JSONArray = rpc("yd_device_catalog", credentials())
    suspend fun requests(): JSONArray = rpc("yd_device_requests", credentials())
    suspend fun appRequests(): JSONArray = rpc("yd_device_app_requests", credentials())

    internal suspend fun sendQueuedRequest(request: QueuedDeviceRequest) {
        val (deviceToken, requestHeaders) = account.requestCredentials(request.scope)
        val body = JSONObject().put("device_token", deviceToken).put("request_reason", request.reason)
            .put("client_request_id", request.id)
        when (request.kind) {
            QueuedDeviceRequest.Kind.SITE -> {
                body.put("site_url", request.target)
                if (request.requestedHours != null || request.requestedUntil != null) {
                    rpc("yd_request_site_window", body
                        .put("requested_hours", request.requestedHours ?: JSONObject.NULL)
                        .put("requested_until", request.requestedUntil?.toString() ?: JSONObject.NULL), requestHeaders)
                } else {
                    rpc("yd_request_site_change", body.put("requested_allowed", request.allowed), requestHeaders)
                }
            }
            QueuedDeviceRequest.Kind.APP -> rpc("yd_request_app_access",
                body.put("package_name", request.target), requestHeaders)
        }
    }

    internal suspend fun devicePolicy(device: Pair<String, String>): DevicePolicy {
        val requestStart = SystemClock.elapsedRealtime()
        val result = rpc("yd_get_device_policy_v2", policyCredential(device)).getJSONObject(0)
        return DevicePolicy.parse(result, requireWindows = true, elapsedAtFetch = requestStart)
    }

    internal suspend fun reportPolicy(device: Pair<String, String>, revision: Long, applied: Boolean): DevicePolicy {
        val requestStart = SystemClock.elapsedRealtime()
        val result = rpc("yd_report_device_policy_v2", policyCredential(device)
            .put("policy_revision", revision).put("applied", applied)).getJSONObject(0)
        return DevicePolicy.parse(result, requireWindows = true, elapsedAtFetch = requestStart)
    }

    private fun policyCredential(device: Pair<String, String>): JSONObject {
        check(device == deviceCredential()) { "SESSION_CHANGED" }
        return JSONObject().put("device_token", device.second)
    }

    internal suspend fun authorizeDownload(connection: HttpURLConnection, url: URL) {
        if (url.protocol == "https" && url.host == "jqrznghqwdjbizjehzrm.supabase.co" &&
            (url.port == -1 || url.port == 443) && url.path.startsWith("/storage/v1/object/authenticated/yd-apks/")) {
            check(paired) { "PAIR_REQUIRED" }
            connection.setRequestProperty("apikey", PUBLIC_KEY)
            connection.setRequestProperty("x-yidream-device-token", account.deviceCredential()!!.second)
            account.requestHeaders().forEach { (name, value) -> connection.setRequestProperty(name, value) }
        }
    }

    private fun credentials(): JSONObject {
        check(paired) { "PAIR_REQUIRED" }
        return JSONObject().put("device_token", account.deviceCredential()!!.second)
    }

    private suspend fun rpc(name: String, input: JSONObject, headers: Map<String, String>? = null): JSONArray = withContext(Dispatchers.IO) {
        val accountHeaders = headers ?: account.requestHeaders()
        val connection = URL("$SERVICE/rest/v1/rpc/$name").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("apikey", PUBLIC_KEY)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            accountHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(input.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { reader ->
                    val buffer = CharArray(512)
                    val length = reader.read(buffer)
                    if (length < 0) "" else String(buffer, 0, length)
                }
                val reason = runCatching { JSONObject(errorResponse.orEmpty()).optString("message") }.getOrNull()
                error(when {
                    reason in setOf("INVALID_APP_REQUEST", "INVALID_SITE_REQUEST", "INVALID_SITE_WINDOW",
                        "SITE_WINDOW_EXPIRED", "SITE_REQUEST_LIMIT", "APP_REQUEST_LIMIT") ->
                        requireNotNull(reason)
                    status == 401 || status == 403 || reason == "APP_ACCESS_DENIED" -> "APP_ACCESS_DENIED"
                    status == 404 -> "SERVICE_NOT_READY"
                    status == 429 || status >= 500 -> "SERVICE_UNAVAILABLE"
                    else -> "SERVICE_ERROR"
                })
            }
            val text = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val output = StringBuilder()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val length = reader.read(buffer)
                    if (length < 0) break
                    check(output.length + length <= 256 * 1024) { "SERVICE_RESPONSE_TOO_LARGE" }
                    output.append(buffer, 0, length)
                }
                output.toString()
            }
            when {
                text == "null" || text.isBlank() -> JSONArray()
                text.startsWith("[") -> JSONArray(text)
                else -> JSONArray().put(JSONObject(text))
            }
        } finally { connection.disconnect() }
    }

    companion object {
        private const val SERVICE = "https://jqrznghqwdjbizjehzrm.supabase.co"
        // Public project key, never a service-role key.
        private const val PUBLIC_KEY = "sb_publishable_YG9zgjQ5Y56WiBtKdJMQvA_-UBAFv9B"
        fun normalizeSite(address: String): String {
            val input = address.trim()
            val uri = URI(if (input.contains("://")) input else "https://$input")
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.host.contains('.') &&
                input.length <= 2048) { "INVALID_SITE" }
            return URI("https", null, uri.host.lowercase(), -1, uri.path.ifBlank { "/" }, uri.query, null).toASCIIString()
        }
    }
}
