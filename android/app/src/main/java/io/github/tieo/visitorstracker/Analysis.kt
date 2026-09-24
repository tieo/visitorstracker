package io.github.tieo.visitorstracker

import io.github.tieo.visitorstracker.source.Flexappoint.BERLIN
import io.github.tieo.visitorstracker.source.Slot
import io.github.tieo.visitorstracker.source.appointments
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Turns the slot history into what the office screens show.
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

data class OfficeState(
    val lastPoll: Poll?,
    val lastOk: Poll?,
    val trackingSince: Instant?,
    val freeNow: Int,
    val nextFree: Instant?,
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

    fun freeAppointments(slots: List<Tracked>): Int {
        val free = slots.filter { it.outcome == Outcome.FREE }.map { Slot(it.start, it.end, it.resource) }
        return if (free.isEmpty()) 0 else appointments(free)
    }

    fun state(polls: List<Poll>, rows: List<SlotRow>, now: Instant): OfficeState {
        val ok = polls.filter { it.ok && !it.partial }
        val slots = tracked(rows, ok.firstOrNull()?.at, now)
        val free = slots.filter { it.outcome == Outcome.FREE }
        return OfficeState(
            lastPoll = polls.lastOrNull(),
            lastOk = ok.lastOrNull(),
            trackingSince = ok.firstOrNull()?.at,
            freeNow = freeAppointments(free),
            nextFree = free.minOfOrNull { it.start },
        )
    }

    fun freeDays(rows: List<SlotRow>, now: Instant): List<FreeDay> =
        rows.filter { it.goneSeen == null && it.start.isAfter(now) }
            .groupBy { it.start.atZone(BERLIN).toLocalDate() }
            .map { (day, group) ->
                FreeDay(day, appointments(group.map { Slot(it.start, it.end, it.resource) }),
                    group.minOf { it.start }, group.maxOf { it.start })
            }
            .sortedBy { it.day }

    /** Free appointments starting on or before [until], and the earliest start. */
    fun freeUntil(open: List<SlotRow>, until: LocalDate, now: Instant): Pair<Int, Instant?> {
        val slots = open.filter { it.start.isAfter(now) && !it.start.atZone(BERLIN).toLocalDate().isAfter(until) }
            .map { Slot(it.start, it.end, it.resource) }
        return if (slots.isEmpty()) 0 to null else appointments(slots) to slots.minOf { it.start }
    }

    private fun hours(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 3_600_000.0

    /** How long slots of a group stay free after release. */
    fun survival(slots: List<Tracked>, now: Instant): Survival {
        val (median, observed) = medianHoursToBooking(slots, now)
        val watched = slots.filter { it.releasedInView }
        return Survival(median, observed, watched.size, watched.count { it.outcome == Outcome.BOOKED })
    }

    /**
     * The office's answer to "which day and which time": survival by weekday
     * of the appointment and by hour within each weekday, plus when new days
     * get released.
     */
    fun insight(polls: List<Poll>, rows: List<SlotRow>, now: Instant): Insight {
        val ok = polls.filter { it.ok }
        val slots = tracked(rows, ok.firstOrNull { !it.partial }?.at, now)
        val local = { slot: Tracked -> slot.start.atZone(BERLIN) }
        val byWeekday = slots.groupBy { local(it).dayOfWeek.value }.mapValues { survival(it.value, now) }
        val byHour = slots.groupBy { local(it).dayOfWeek.value }.mapValues { (_, day) ->
            day.groupBy { local(it).hour }.mapValues { survival(it.value, now) }
        }
        return Insight(byWeekday, byHour, releases(ok, rows), survival(slots, now),
            slots.count { it.releasedInView }, ok.firstOrNull()?.at)
    }

    /**
     * Days that appeared while the office was watched. A day's release lies
     * between the last poll without it and the first poll with it.
     */
    fun releases(okPolls: List<Poll>, rows: List<SlotRow>): List<Release> {
        val first = okPolls.firstOrNull()?.at ?: return emptyList()
        val times = okPolls.map { it.at }
        return rows.groupBy { it.start.atZone(BERLIN).toLocalDate() }
            .mapNotNull { (day, group) ->
                val seen = group.minOf { it.firstSeen }
                if (seen == first) return@mapNotNull null
                val before = times.lastOrNull { it.isBefore(seen) } ?: return@mapNotNull null
                Release(day, before, seen)
            }
            .sortedBy { it.seen }
    }

    /**
     * The usual release time as minutes after local midnight: the median of
     * the latest bound across recent releases, with the earliest bound, so a
     * dense watch can cover the whole span.
     */
    fun releaseWindow(releases: List<Release>, recent: Int = 7): Pair<Int, Int>? {
        val last = releases.takeLast(recent).filter { Duration.between(it.before, it.seen) < Duration.ofHours(3) }
        if (last.isEmpty()) return null
        fun minute(at: Instant) = at.atZone(BERLIN).let { it.hour * 60 + it.minute }
        // Around midnight a plain median of minutes would split 23:58 and 00:02; shift by twelve hours first.
        fun median(values: List<Int>): Int {
            val shifted = values.map { (it + 720) % 1440 }.sorted()
            return (shifted[shifted.size / 2] + 720) % 1440
        }
        return median(last.map { minute(it.before) }) to median(last.map { minute(it.seen) })
    }
}

/** Half of the slots were booked after [medianHours]; null while more than half are still free after [observedHours]. */
data class Survival(val medianHours: Double?, val observedHours: Double, val released: Int, val booked: Int) {
    /** Enough released slots to say anything. */
    val known: Boolean get() = released >= MIN_RELEASED

    /** Hours the slots last, as far as measured; a lower bound while [medianHours] is null. */
    val hours: Double get() = medianHours ?: observedHours

    companion object {
        const val MIN_RELEASED = 20
    }
}

data class Release(val day: LocalDate, val before: Instant, val seen: Instant)

/** One appointment day with free time: how many appointments fit, and the first and last free start. */
data class FreeDay(val day: LocalDate, val appointments: Int, val first: Instant, val last: Instant)

data class Insight(
    val byWeekday: Map<Int, Survival>,
    val byHour: Map<Int, Map<Int, Survival>>,
    val releases: List<Release>,
    val overall: Survival,
    val released: Int,
    val since: Instant?,
) {
    /**
     * The weekday whose slots last longest, and the one whose go fastest. A
     * ranking needs at least two groups with enough released slots; one group
     * alone is no comparison.
     */
    fun easiestDay(): Int? = easiest(byWeekday)
    fun hardestDay(): Int? = hardest(byWeekday)
    fun easiestHour(weekday: Int): Int? = easiest(byHour[weekday].orEmpty())
    fun hardestHour(weekday: Int): Int? = hardest(byHour[weekday].orEmpty())

    private fun easiest(groups: Map<Int, Survival>): Int? {
        val known = groups.filterValues { it.known }
        return if (known.size < 2) null else known.maxByOrNull { it.value.hours }?.key
    }

    private fun hardest(groups: Map<Int, Survival>): Int? {
        val known = groups.filterValues { it.known }
        if (known.size < 2) return null
        return known.filterValues { it.medianHours != null }.minByOrNull { it.value.hours }?.key
    }
}
