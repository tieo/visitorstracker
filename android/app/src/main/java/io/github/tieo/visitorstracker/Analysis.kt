package io.github.tieo.visitorstracker

import io.github.tieo.visitorstracker.source.Flexappoint.BERLIN
import io.github.tieo.visitorstracker.source.Slot
import io.github.tieo.visitorstracker.source.appointments
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Turns the slot history into what the office screen shows.
 *
 * A slot that vanished was either booked or dropped out of the bookable
 * window. Booking systems stop offering a slot some lead time before it
 * starts; that lead is estimated per office as the shortest lead at which a
 * slot was ever still shown. A slot that vanished with less lead than that ran
 * out of the window unbooked; any other vanishing counts as booked.
 *
 * How fast slots go is measured from release (the first poll showing the
 * slot) to booking. Slots still free, or that ran out unbooked, are
 * right-censored, so the median comes from a Kaplan-Meier estimate rather than
 * from booked slots alone, which would make every cell look fast. Slots already
 * free at the office's first poll have an unknown release time and are left out.
 */
enum class Outcome { FREE, BOOKED, EXPIRED }

data class Tracked(
    val start: Instant,
    val end: Instant,
    val resource: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val goneSeen: Instant?,
    val releasedInView: Boolean,
    val outcome: Outcome,
)

data class Cell(
    val median: Double?,
    val observed: Double,
    val measured: Int,
    val booked: Int,
    val expired: Int,
    val free: Int,
)

/** Weekday (1 = Monday) and minute of day, rounded down to [BUCKET_MINUTES]. */
data class CellKey(val weekday: Int, val minute: Int)

const val BUCKET_MINUTES = 30

data class OfficeState(
    val lastPoll: Poll?,
    val lastOk: Poll?,
    val trackingSince: Instant?,
    val freeNow: Int,
    val nextFree: Instant?,
    val cells: Map<CellKey, Cell>,
    val freeByDay: List<Pair<LocalDate, Int>>,
    val timeline: List<Pair<Instant, Int>>,
)

object Analysis {
    fun tracked(rows: List<SlotRow>, firstOk: Instant?, now: Instant): List<Tracked> {
        if (rows.isEmpty()) return emptyList()
        val shortestLead = rows.minOf { Duration.between(it.lastSeen, it.start) }
        return rows.map { row ->
            val gone = row.goneSeen
            val outcome = when {
                gone == null -> if (row.start.isAfter(now)) Outcome.FREE else Outcome.EXPIRED
                Duration.between(gone, row.start) < shortestLead -> Outcome.EXPIRED
                else -> Outcome.BOOKED
            }
            Tracked(row.start, row.end, row.resource, row.firstSeen, row.lastSeen, gone,
                row.firstSeen != firstOk, outcome)
        }
    }

    /**
     * Kaplan-Meier median of release-to-booking time, in hours, and the longest
     * observed time. The median is null when fewer than half of the slots were
     * booked within the observed time.
     */
    fun medianHoursToBooking(slots: List<Tracked>, now: Instant): Pair<Double?, Double> {
        val samples = slots.filter { it.releasedInView }.map { slot ->
            if (slot.outcome == Outcome.BOOKED) {
                // Booked somewhere between the last poll showing it and the next.
                val moment = slot.lastSeen.plus(Duration.between(slot.lastSeen, slot.goneSeen!!).dividedBy(2))
                hours(slot.firstSeen, moment) to true
            } else {
                val end = slot.goneSeen?.let { slot.lastSeen } ?: minOf(now, slot.start)
                hours(slot.firstSeen, end) to false
            }
        }.sortedBy { it.first }
        if (samples.isEmpty()) return null to 0.0
        var atRisk = samples.size
        var survival = 1.0
        var index = 0
        while (index < samples.size) {
            val hours = samples[index].first
            var events = 0
            var censored = 0
            while (index < samples.size && samples[index].first == hours) {
                if (samples[index].second) events++ else censored++
                index++
            }
            if (events > 0) {
                survival *= 1 - events.toDouble() / atRisk
                if (survival <= 0.5) return hours to samples.last().first
            }
            atRisk -= events + censored
        }
        return null to samples.last().first
    }

    fun key(moment: Instant): CellKey {
        val local = moment.atZone(BERLIN)
        val minute = local.hour * 60 + local.minute
        return CellKey(local.dayOfWeek.value, minute - minute % BUCKET_MINUTES)
    }

    fun cells(slots: List<Tracked>, now: Instant): Map<CellKey, Cell> =
        slots.groupBy { key(it.start) }.mapValues { (_, members) ->
            val (median, observed) = medianHoursToBooking(members, now)
            Cell(median, observed, members.count { it.releasedInView },
                members.count { it.outcome == Outcome.BOOKED },
                members.count { it.outcome == Outcome.EXPIRED },
                members.count { it.outcome == Outcome.FREE })
        }

    fun freeAppointments(slots: List<Tracked>): Int {
        val free = slots.filter { it.outcome == Outcome.FREE }.map { Slot(it.start, it.end, it.resource) }
        return if (free.isEmpty()) 0 else appointments(free)
    }

    fun freeByDay(slots: List<Tracked>): List<Pair<LocalDate, Int>> =
        slots.filter { it.outcome == Outcome.FREE }
            .groupBy { it.start.atZone(BERLIN).toLocalDate() }
            .map { (day, group) -> day to freeAppointments(group) }
            .sortedBy { it.first }

    fun state(polls: List<Poll>, rows: List<SlotRow>, now: Instant): OfficeState {
        val ok = polls.filter { it.ok }
        val slots = tracked(rows, ok.firstOrNull()?.at, now)
        val free = slots.filter { it.outcome == Outcome.FREE }
        return OfficeState(
            lastPoll = polls.lastOrNull(),
            lastOk = ok.lastOrNull(),
            trackingSince = ok.firstOrNull()?.at,
            freeNow = freeAppointments(free),
            nextFree = free.minOfOrNull { it.start },
            cells = cells(slots, now),
            freeByDay = freeByDay(slots),
            timeline = ok.mapNotNull { poll -> poll.appointments?.let { poll.at to it } },
        )
    }

    /** Free appointments starting on or before [until], and the earliest start. */
    fun freeUntil(open: List<SlotRow>, until: LocalDate, now: Instant): Pair<Int, Instant?> {
        val slots = open.filter { it.start.isAfter(now) && !it.start.atZone(BERLIN).toLocalDate().isAfter(until) }
            .map { Slot(it.start, it.end, it.resource) }
        return if (slots.isEmpty()) 0 to null else appointments(slots) to slots.minOf { it.start }
    }

    private fun hours(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 3_600_000.0
}
