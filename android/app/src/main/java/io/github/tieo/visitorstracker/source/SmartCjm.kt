package io.github.tieo.visitorstracker.source

import java.io.IOException
import java.net.URLDecoder
import java.time.OffsetDateTime

/**
 * smartCJM appointment wizard (used by the Landratsamt Alb-Donau-Kreis).
 *
 * The wizard is server-rendered: each step is a form POST carrying an
 * anti-forgery token and the current step index. Some calendars first ask for
 * the postcode of the applicant's residence, then for the services, optionally
 * for the location, and then render every free start time as a button calling
 * `appointment_reserve(start, duration, location, resource)`. Reading stops at
 * that page; nothing is ever reserved.
 */
object SmartCjm {
    private val form = Regex("""<form id="calendar_[^"]*"[^>]*action="([^"]+)"""")
    private val token = Regex("""name='__RequestVerificationToken' value='([^']+)'""")
    private val stepFields = Regex(
        """<input type="hidden" id="(?:steps|step_current|step_current_index)" name="([^"]+)" value="([^"]*)"""",
    )
    private val stepCurrent = Regex("""id="step_current" name="step_current" value="([^"]*)"""")
    private val reserve = Regex("""appointment_reserve\('([^']+)', '(\d+)', '([^']+)', '([^']+)'\)""")

    fun read(office: SmartCjmOffice, http: Http): List<Slot> {
        var page = http.get(office.url + "?uid=" + office.uid)
        if (page.contains("name=\"zip_code\"")) page = next(http, office.url, page, listOf("zip_code" to office.zipCode))
        page = next(http, office.url, page,
            listOf("services" to office.service, "service_${office.service}_amount" to "1"))
        if (step(page) == "locations") {
            page = next(http, office.url, page, listOf("locations" to (office.location ?: throw IOException("calendar asks for a location"))))
        }
        if (step(page) != "search_results") throw IOException("expected search_results, got ${step(page)}")
        return slots(page)
    }

    fun step(page: String): String? = stepCurrent.find(page)?.groupValues?.get(1)

    /** Free slots listed on a search_results page. */
    fun slots(page: String): List<Slot> =
        reserve.findAll(page).map { match ->
            val (start, minutes, location, resource) = match.destructured
            val begin = OffsetDateTime.parse(URLDecoder.decode(start, "UTF-8")).toInstant()
            // One calendar can span several locations, each with its own counters.
            Slot(begin, begin.plusSeconds(minutes.toLong() * 60), "$location/$resource")
        }.distinct().sortedWith(compareBy({ it.start }, { it.resource })).toList()

    private fun next(http: Http, url: String, page: String, fields: List<Pair<String, String>>): String {
        val action = form.find(page)?.groupValues?.get(1) ?: throw IOException("wizard form not found")
        val verification = token.find(page)?.groupValues?.get(1) ?: throw IOException("wizard token not found")
        val data = buildList {
            add("__RequestVerificationToken" to verification)
            add("action_type" to "")
            stepFields.findAll(page).forEach { add(it.groupValues[1] to it.groupValues[2]) }
            add("step_goto" to "+1")
            addAll(fields)
        }
        return http.post(url + action.replace("&amp;", "&"), data)
    }
}
