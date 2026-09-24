package io.github.tieo.visitorstracker.source

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * flexappoint JSON API (used by the Stadt Ehingen).
 *
 * `/api/available-times/disabled-days` lists the days of a month without any
 * free time for the chosen service; every other day up to the horizon is then
 * asked for its free times. A time carries `count`, the number of parallel
 * appointments still bookable at that start, and each of them becomes a slot.
 * Times are local to the office.
 */
object Flexappoint {
    val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Free slots from [from] (today when null) to the horizon. Reading only the
     * newest days keeps the dense rounds around a release light.
     */
    fun read(office: FlexappointOffice, http: Http, from: LocalDate? = null, pauseMillis: Long = 1000): List<Slot> {
        val api = office.url.trimEnd('/') + "/api"
        val service = mapOf("[0][id]" to office.service.toString(), "[0][count]" to "1")
        val today = maxOf(LocalDate.now(BERLIN), from ?: LocalDate.MIN)
        val last = LocalDate.now(BERLIN).plusDays(office.horizonDays)

        val disabled = mutableSetOf<String>()
        var month = today.withDayOfMonth(1)
        while (!month.isAfter(last)) {
            val params = mapOf(
                "departmentId" to office.department.toString(), "year" to month.year.toString(),
                "month" to month.monthValue.toString(), "format" to "Y-m-d",
            ) + service.mapKeys { "services" + it.key }
            items(http.get(url("$api/available-times/disabled-days", params), AJAX))
                .forEach { disabled.add(it.jsonPrimitive.content) }
            Thread.sleep(pauseMillis)
            month = month.plusMonths(1)
        }

        val slots = mutableListOf<Slot>()
        var day = today
        while (!day.isAfter(last)) {
            if (day.toString() !in disabled) {
                val params = mapOf("departmentId" to office.department.toString(), "date" to day.toString()) +
                    service.mapKeys { "service" + it.key }
                slots += times(day, http.get(url("$api/available-times", params), AJAX))
                Thread.sleep(pauseMillis)
            }
            day = day.plusDays(1)
        }
        return slots
    }

    /** Slots of one day's `available-times` answer. */
    fun times(day: LocalDate, body: String): List<Slot> = items(body).flatMap { element ->
        val item = element.jsonObject
        val start = at(day, item["start_time"]!!.jsonPrimitive.content)
        val end = at(day, item["end_time"]!!.jsonPrimitive.content)
        (0 until item["count"]!!.jsonPrimitive.int).map { Slot(start, end, "#$it") }
    }

    private fun items(body: String): List<kotlinx.serialization.json.JsonElement> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: throw IOException("unexpected answer")
        if (root["status"]?.jsonPrimitive?.boolean == false) {
            throw IOException(root["message"]?.jsonPrimitive?.content ?: "request failed")
        }
        return root["items"]?.jsonArray.orEmpty()
    }

    private fun at(day: LocalDate, clock: String) =
        ZonedDateTime.of(day, LocalTime.parse(clock), BERLIN).toInstant()

    private fun url(base: String, params: Map<String, String>) =
        base + "?" + params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    private val AJAX = mapOf("X-Requested-With" to "XMLHttpRequest")
}
