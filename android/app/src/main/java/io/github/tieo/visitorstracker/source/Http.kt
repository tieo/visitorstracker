package io.github.tieo.visitorstracker.source

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A small HTTP client with its own cookie jar, so one booking-wizard session
 * keeps its cookies across redirects and form posts. Blocking; call it off
 * the main thread.
 */
class Http(private val userAgent: String = USER_AGENT) {
    private val cookies = linkedMapOf<String, String>()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request("GET", url, null, headers)

    fun post(url: String, form: List<Pair<String, String>>): String {
        val body = form.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }
        return request("POST", url, body, mapOf("Content-Type" to "application/x-www-form-urlencoded"))
    }

    private fun request(method: String, start: String, body: String?, headers: Map<String, String>): String {
        var url = start
        var verb = method
        var payload = body
        repeat(MAX_REDIRECTS) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.requestMethod = verb
                connection.setRequestProperty("User-Agent", userAgent)
                if (cookies.isNotEmpty()) {
                    connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
                headers.forEach(connection::setRequestProperty)
                if (payload != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(payload!!.toByteArray()) }
                }
                val code = connection.responseCode
                keepCookies(connection)
                when {
                    code == 429 -> throw RateLimited(connection.getHeaderField("Retry-After")?.toLongOrNull())
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("redirect without Location")
                        url = URL(URL(url), location).toString()
                        // A redirected form post continues as a plain GET, as browsers do.
                        verb = "GET"
                        payload = null
                    }
                    code >= 400 -> throw IOException("HTTP $code from $url")
                    else -> return connection.inputStream.bufferedReader().use { it.readText() }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("too many redirects from $start")
    }

    private fun keepCookies(connection: HttpURLConnection) {
        connection.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }
            .values.flatten().forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty() && pair.contains('=')) cookies[name] = pair.substringAfter('=').trim()
            }
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    companion object {
        const val USER_AGENT = "visitorstracker (appointment availability statistics)"
        private const val MAX_REDIRECTS = 6
    }
}
