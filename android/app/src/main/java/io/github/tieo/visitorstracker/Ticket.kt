package io.github.tieo.visitorstracker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * A walk-in ticket of a smartCJM office, read from its "Mobile Info" page.
 *
 * The page embeds its state as JSON in a hidden `hidden_check_data` element
 * and refreshes it by fetching itself with `&api=true`. While the ticket waits,
 * the JSON carries the position in the queue; once the ticket is served or
 * cancelled the page no longer carries the element at all.
 */
@Serializable
data class TicketStatus(
    val status: String,
    val position: Int? = null,
    @SerialName("waiting_count") val waitingCount: Int? = null,
    @SerialName("avg_waiting_time") val averageWait: String? = null,
    @SerialName("waiting_time") val waited: String? = null,
) {
    val waiting: Boolean get() = status == "waiting"
}

object Ticket {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val checkData = Regex("""hidden_check_data">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val urlInText = Regex("""https://\S+""")
    private val ticketNumber = Regex("""\b([A-Z]\d{2,4})\b""")
    private val tag = Regex("""<[^>]+>""")
    private val hidden = Regex("""<(script|style)[^>]*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL)

    /** The walk-in page link inside shared text, if the text carries one. */
    fun findUrl(text: String): String? =
        urlInText.findAll(text).map { it.value.trimEnd('.', ',', ')') }.firstOrNull(::isTicketUrl)

    fun isTicketUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host.orEmpty().endsWith(".smartcjm.com") &&
            uri.path.orEmpty().endsWith("/extern/info/walkin") &&
            uri.query.orEmpty().contains("uid=")
    }

    fun statusUrl(url: String): String = if (url.contains("api=true")) url else "$url&api=true"

    /** The ticket's state, or null when the page no longer shows a ticket. */
    fun parse(page: String): TicketStatus? {
        val raw = checkData.find(page)?.groupValues?.get(1)?.let(::unescape)?.trim() ?: return null
        return runCatching { json.decodeFromString<TicketStatus>(raw) }.getOrNull()
    }

    /** The number shown on the ticket (like "E137"), when the page names one. */
    fun number(page: String): String? {
        val body = page.substringAfter("<body", page)
        val text = tag.replace(hidden.replace(body, " "), " ")
        return ticketNumber.find(text)?.groupValues?.get(1)
    }

    private fun unescape(text: String) =
        text.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&amp;", "&")
}
