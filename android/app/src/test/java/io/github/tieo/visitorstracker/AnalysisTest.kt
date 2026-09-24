package io.github.tieo.visitorstracker

import io.github.tieo.visitorstracker.source.Flexappoint
import io.github.tieo.visitorstracker.source.SmartCjm
import io.github.tieo.visitorstracker.source.Slot
import io.github.tieo.visitorstracker.source.appointments
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalysisTest {
    // Mon 5 Oct 2026, 09:00 in Berlin.
    private val t0: Instant = Instant.parse("2026-10-05T07:00:00Z")

    private fun slot(minute: Long, length: Long = 10, resource: String = "a") =
        Slot(t0.plusSeconds(minute * 60), t0.plusSeconds((minute + length) * 60), resource)

    @Test
    fun overlappingStartTimesCountAsAppointmentsPerStretch() {
        // A 10 minute service offered every 5 minutes: 09:00 to 09:30 holds three.
        assertEquals(3, appointments(listOf(0L, 5, 10, 15, 20).map { slot(it) }))
    }

    @Test
    fun countersAreCountedSeparately() {
        assertEquals(2, appointments(listOf(slot(0, resource = "a"), slot(0, resource = "b"))))
    }

    private fun row(id: Long, first: Instant, last: Instant, gone: Instant?, start: Instant = t0) =
        SlotRow(id, start, start.plusSeconds(600), "a", first, last, gone)

    @Test
    fun vanishingInsideTheBookingWindowIsABooking() {
        val first = t0.minus(Duration.ofDays(3))
        val rows = listOf(
            // Still shown one hour before start: the window reaches that close.
            row(1, first, t0.minus(Duration.ofHours(1)), t0.minus(Duration.ofMinutes(50)), t0),
            // Gone two days before start: booked.
            row(2, first, first.plus(Duration.ofHours(1)), first.plus(Duration.ofHours(2)), t0.plusSeconds(900)),
        )
        val tracked = Analysis.tracked(rows, first, t0.minus(Duration.ofDays(1)))
        assertEquals(listOf(Outcome.EXPIRED, Outcome.BOOKED), tracked.map { it.outcome })
    }

    @Test
    fun medianStaysOpenWhileMostSlotsAreFree() {
        val now = t0.minus(Duration.ofDays(1))
        val base = now.minus(Duration.ofDays(2))
        val slots = List(3) { Tracked(t0, t0, "a", base, now, null, true, Outcome.FREE) } +
            Tracked(t0, t0, "a", base, base, base.plus(Duration.ofHours(2)), true, Outcome.BOOKED)
        val (median, observed) = Analysis.medianHoursToBooking(slots, now)
        assertNull(median)
        assertEquals(48.0, observed, 0.01)
    }

    @Test
    fun medianOfQuicklyBookedSlots() {
        val base = t0.minus(Duration.ofDays(2))
        val slots = List(3) { index ->
            Tracked(t0, t0, "a", base, base, base.plus(Duration.ofHours(index + 1L)), true, Outcome.BOOKED)
        }
        // Booked between polls, counted at the midpoint: 0.5 h, 1 h, 1.5 h.
        assertEquals(1.0, Analysis.medianHoursToBooking(slots, t0).first!!, 0.01)
    }

    @Test
    fun releasesAreBoundedByThePollBefore() {
        val polls = listOf(0L, 15, 30).map { Poll(t0.plusSeconds(it * 60), true, 1, 1, null) }
        val rows = listOf(
            row(1, t0, t0, null, t0.plus(Duration.ofDays(10))),
            row(2, t0.plusSeconds(30 * 60), t0.plusSeconds(30 * 60), null, t0.plus(Duration.ofDays(11))),
        )
        val releases = Analysis.releases(polls, rows)
        assertEquals(1, releases.size)
        assertEquals(t0.plusSeconds(15 * 60), releases[0].before)
        assertEquals(LocalDate.of(2026, 10, 16), releases[0].day)
    }

    @Test
    fun releaseWindowAroundMidnightDoesNotSplit() {
        fun at(text: String) = Instant.parse(text)
        val releases = listOf(
            Release(LocalDate.of(2026, 10, 9), at("2026-09-24T21:55:00Z"), at("2026-09-24T22:02:00Z")),
            Release(LocalDate.of(2026, 10, 12), at("2026-09-27T21:58:00Z"), at("2026-09-27T22:04:00Z")),
            Release(LocalDate.of(2026, 10, 13), at("2026-09-28T22:01:00Z"), at("2026-09-28T22:03:00Z")),
        )
        // 23:58 and 00:03 Berlin summer time, not a midnight-straddling average of noon.
        assertEquals(23 * 60 + 58 to 3, Analysis.releaseWindow(releases))
    }

    @Test
    fun easiestDayNeedsEnoughReleasedSlots() {
        val base = t0.minus(Duration.ofDays(3))
        fun slots(weekdayOffset: Long, count: Int, bookedAfterHours: Long?) = List(count) {
            val start = t0.plus(Duration.ofDays(weekdayOffset))
            Tracked(start, start, "a", base, bookedAfterHours?.let { base.plus(Duration.ofHours(it)) } ?: t0,
                bookedAfterHours?.let { base.plus(Duration.ofHours(it + 1)) }, true,
                if (bookedAfterHours == null) Outcome.FREE else Outcome.BOOKED)
        }
        val monday = Analysis.survival(slots(0, 24, 1), t0)
        val tuesday = Analysis.survival(slots(1, 24, null), t0)
        val wednesday = Analysis.survival(slots(2, 2, null), t0)
        val insight = Insight(mapOf(1 to monday, 2 to tuesday, 3 to wednesday), emptyMap(), emptyList(), monday, 50, base)
        assertNull(Insight(mapOf(1 to monday), emptyMap(), emptyList(), monday, 24, base).easiestDay())
        assertEquals(2, insight.easiestDay())
        assertEquals(1, insight.hardestDay())
    }

    @Test
    fun freeUntilCountsAppointmentsUpToTheDate() {
        val rows = (0 until 30).map { n -> row(n.toLong(), t0, t0, null, t0.plusSeconds(n * 300L)) } +
            row(99, t0, t0, null, t0.plus(Duration.ofDays(5)))
        val (count, first) = Analysis.freeUntil(rows, LocalDate.of(2026, 10, 5), t0.minusSeconds(60))
        assertEquals(15, count)
        assertEquals(t0, first)
    }

    @Test
    fun readsSmartCjmSearchResults() {
        val page = """
            <input type="hidden" id="step_current" name="step_current" value="search_results" />
            <button onclick="return appointment_reserve('2026-10-08T09%3a30%3a00%2b02%3a00', '10', 'loc', 'r1');">
            <button onclick="return appointment_reserve('2026-10-08T09%3a30%3a00%2b02%3a00', '10', 'loc', 'r2');">
            <button onclick="return appointment_reserve('2026-10-08T09%3a35%3a00%2b02%3a00', '10', 'loc', 'r1');">
        """.trimIndent()
        assertEquals("search_results", SmartCjm.step(page))
        val slots = SmartCjm.slots(page)
        assertEquals(3, slots.size)
        assertEquals(Instant.parse("2026-10-08T07:30:00Z"), slots[0].start)
        assertEquals(Instant.parse("2026-10-08T07:40:00Z"), slots[0].end)
        assertEquals("loc/r1", slots[0].resource)
    }

    @Test
    fun readsFlexappointTimesWithCounts() {
        val body = """{"status":true,"items":[
            {"start_time":"08:00","end_time":"08:10","count":2},
            {"start_time":"08:15","end_time":"08:25","count":1}]}"""
        val slots = Flexappoint.times(LocalDate.of(2026, 10, 6), body)
        assertEquals(3, slots.size)
        assertEquals(Instant.parse("2026-10-06T06:00:00Z"), slots[0].start)
        assertEquals(listOf("#0", "#1", "#0"), slots.map { it.resource })
    }
}
