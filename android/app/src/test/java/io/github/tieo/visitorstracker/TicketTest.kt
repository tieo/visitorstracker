package io.github.tieo.visitorstracker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TicketTest {
    private val url =
        "https://example.saas.smartcjm.com/m/abh-fs/extern/info/walkin" +
            "?uid=00000000-1111-2222-3333-444444444444&appointment=0&sec=abc"

    private val waitingPage = """
        <html><head><style>.x{}</style><script>var y = "Z999";</script></head>
        <body><h1>Mobile Info</h1><div id="nr">E137</div>
        <div class="hidden_check_data">{
            &quot;status&quot;: &quot;waiting&quot;,
            &quot;waiting_time&quot;: &quot;2&quot;,
            &quot;avg_waiting_time&quot;: &quot;29&quot;,
            &quot;position&quot;: 9,
            &quot;waiting_count&quot;: 11
        }</div></body></html>
    """.trimIndent()

    @Test
    fun readsWaitingState() {
        val status = Ticket.parse(waitingPage)!!
        assertTrue(status.waiting)
        assertEquals(9, status.position)
        assertEquals(11, status.waitingCount)
        assertEquals("29", status.averageWait)
    }

    @Test
    fun readsPlainJsonToo() {
        val page = """<div class="hidden_check_data">{"status":"called","position":0}</div>"""
        val status = Ticket.parse(page)!!
        assertFalse(status.waiting)
        assertEquals("called", status.status)
    }

    @Test
    fun finishedTicketHasNoState() {
        assertNull(Ticket.parse("<body>Mobile Info Impressum | Datenschutz</body>"))
    }

    @Test
    fun ticketNumberComesFromTheBodyNotScripts() {
        assertEquals("E137", Ticket.number(waitingPage))
    }

    @Test
    fun findsTicketLinkInSharedText() {
        assertEquals(url, Ticket.findUrl("Mein Ticket: $url."))
        assertNull(Ticket.findUrl("https://example.org/walkin?uid=1"))
        assertFalse(Ticket.isTicketUrl("http://example.saas.smartcjm.com/m/x/extern/info/walkin?uid=1"))
    }

    @Test
    fun statusUrlAsksForTheApiVariantOnce() {
        assertEquals("$url&api=true", Ticket.statusUrl(url))
        assertEquals("$url&api=true", Ticket.statusUrl("$url&api=true"))
    }
}
