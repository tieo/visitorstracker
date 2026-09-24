package io.github.tieo.visitorstracker.source

import java.time.Duration
import java.time.Instant

/**
 * One free unit of capacity: a start time at one counter. Parallel counters
 * offering the same start time are separate slots.
 */
data class Slot(val start: Instant, val end: Instant, val resource: String)

/** The booking system answered HTTP 429; [retryAfterSeconds] is its Retry-After, when given. */
class RateLimited(val retryAfterSeconds: Long?) : Exception("HTTP 429, retry after ${retryAfterSeconds}s")

/**
 * How many appointments the free slots can still hold.
 *
 * Start times overlap: a 10 minute service offered every 5 minutes shows two
 * start times per appointment. Each counter's free time is merged into
 * stretches and every stretch holds as many whole appointments as fit.
 */
fun appointments(slots: Collection<Slot>): Int {
    var total = 0
    for (group in slots.groupBy { it.resource }.values) {
        val sorted = group.sortedBy { it.start }
        val length = Duration.between(sorted[0].start, sorted[0].end)
        if (length.isZero || length.isNegative) continue
        var begin = sorted[0].start
        var end = sorted[0].end
        for (slot in sorted.drop(1)) {
            if (!slot.start.isAfter(end)) {
                if (slot.end.isAfter(end)) end = slot.end
            } else {
                total += (Duration.between(begin, end).toMillis() / length.toMillis()).toInt()
                begin = slot.start
                end = slot.end
            }
        }
        total += (Duration.between(begin, end).toMillis() / length.toMillis()).toInt()
    }
    return total
}
