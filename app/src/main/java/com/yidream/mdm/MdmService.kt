package com.yidream.mdm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/** Device credentials are scoped to one device; administrator sessions never enter the APK. */
class MdmService(context: Context) {
    private val account = AppAccountService(context)
    val paired: Boolean get() = account.deviceCredential() != null

    suspend fun pair(code: String) {
        val result = rpc("yd_pair_device", JSONObject().put("pairing_code", code.trim())).getJSONObject(0)
        account.saveDeviceCredential(result)
    }

    suspend fun catalog(): JSONArray = rpc("yd_device_catalog", credentials())
    suspend fun requests(): JSONArray = rpc("yd_device_requests", credentials())
    suspend fun requestSite(address: String, reason: String, requestId: String) {
        val normalized = normalizeSite(address)
        rpc("yd_request_site", credentials().put("site_url", normalized)
            .put("request_reason", reason.trim()).put("client_request_id", requestId))
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

    private suspend fun rpc(name: String, input: JSONObject): JSONArray = withContext(Dispatchers.IO) {
        val accountHeaders = account.requestHeaders()
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
                connection.errorStream?.close()
                error(if (status == 404) "SERVICE_NOT_READY" else "SERVICE_ERROR")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
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
