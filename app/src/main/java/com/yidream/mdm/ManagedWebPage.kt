package com.yidream.mdm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale

/** The reader fetches each top-level HTTPS page itself so every redirect is checked. */
internal data class ManagedWebPage(val address: URI, val html: String) {
    companion object {
        private const val MAX_PAGE_BYTES = 1_500_000
        private val charset = Regex("""(?:^|;)\s*charset\s*=\s*["']?([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        private const val CONTENT_POLICY = """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'">"""

        fun redirect(address: URI, location: String?, permits: (URI) -> Boolean): URI {
            require(!location.isNullOrBlank() && location.length <= 2048) { "SITE_UNAVAILABLE" }
            return address.resolve(location).also { require(permits(it)) { "SITE_BLOCKED" } }
        }

        suspend fun load(initial: URI, permits: (URI) -> Boolean): ManagedWebPage = withContext(Dispatchers.IO) {
            var address = initial
            repeat(6) {
                currentCoroutineContext().ensureActive()
                require(permits(address)) { "SITE_BLOCKED" }
                val connection = address.toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = 8000
                    connection.readTimeout = 12000
                    connection.setRequestProperty("Accept", "text/html, application/xhtml+xml, text/plain")
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        address = redirect(address, connection.getHeaderField("Location"), permits)
                        return@repeat
                    }
                    check(status in 200..299) { "SITE_UNAVAILABLE" }
                    val contentType = connection.contentType.orEmpty()
                    val mime = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
                    check(mime in listOf("text/html", "application/xhtml+xml", "text/plain")) { "SITE_UNSUPPORTED" }
                    check(connection.contentLengthLong <= MAX_PAGE_BYTES) { "SITE_UNSUPPORTED" }
                    val bytes = connection.inputStream.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = stream.read(buffer)
                            if (size < 0) break
                            check(output.size() + size <= MAX_PAGE_BYTES) { "SITE_UNSUPPORTED" }
                            output.write(buffer, 0, size)
                        }
                        output.toByteArray()
                    }
                    val encoding = charset.find(contentType)?.groupValues?.get(1)?.let {
                        runCatching { Charset.forName(it) }.getOrNull()
                    } ?: Charsets.UTF_8
                    val content = String(bytes, encoding)
                    val html = if (mime == "text/plain") "<pre style=\"white-space:pre-wrap\">" +
                        content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>"
                    else content
                    require(permits(address)) { "SITE_BLOCKED" }
                    return@withContext ManagedWebPage(address, CONTENT_POLICY + html)
                } finally {
                    connection.disconnect()
                }
            }
            error("SITE_UNAVAILABLE")
        }
    }
}
