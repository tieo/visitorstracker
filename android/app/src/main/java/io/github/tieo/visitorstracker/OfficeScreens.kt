package io.github.tieo.visitorstracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tieo.visitorstracker.source.Flexappoint.BERLIN
import io.github.tieo.visitorstracker.source.OFFICES
import io.github.tieo.visitorstracker.source.Office
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything an office's screens show, recomputed whenever the store changes. */
data class OfficeData(
    val state: OfficeState,
    val insight: Insight,
    val freeDays: List<FreeDay>,
    /** The office's next dense release watch, while the background history runs. */
    val watch: Pair<Instant, Instant>?,
)

@Composable
fun officeData(office: Office): OfficeData? {
    val context = LocalContext.current
    val version by Store.changes.collectAsState()
    return produceState<OfficeData?>(null, office.id, version) {
        value = withContext(Dispatchers.IO) {
            val store = Store.get(context)
            val polls = store.polls(office.id)
            val rows = store.slots(office.id)
            val now = Instant.now()
            val insight = Analysis.insight(polls, rows, now)
            val span = Analysis.releaseWindow(insight.releases) ?: ReleasePlanner.DEFAULT_SPAN
            val watch = if (Polling.load(context).history) ReleasePlanner.period(span, now) else null
            OfficeData(Analysis.state(polls, rows, now), insight, Analysis.freeDays(rows, now), watch)
        }
    }.value
}

private val dayMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

private fun clock(moment: Instant) = moment.atZone(BERLIN).format(clockFormat)
private fun minuteClock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

/** The one-line verdict a list row gives for an office. */
private fun verdict(data: OfficeData): String {
    val insight = data.insight
    val easiest = insight.easiestDay()
    val hardest = insight.hardestDay()
    return when {
        data.state.lastOk == null -> "Not read yet"
        easiest != null && hardest != null && hardest != easiest ->
            "Easiest ${WEEKDAYS_SHORT[easiest - 1]} · hardest ${WEEKDAYS_SHORT[hardest - 1]}"
        easiest != null -> "Easiest ${WEEKDAYS_SHORT[easiest - 1]}"
        else -> "Learning · ${insight.released} released slots seen"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficesScreen(modifier: Modifier, onOpen: (Office) -> Unit) {
    val context = LocalContext.current
    val running by Collector.running.collectAsState()
    PullToRefreshBox(isRefreshing = running, onRefresh = { Collector.runNow(context) }, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(OFFICES, key = { it.id }) { office ->
                val data = officeData(office)
                Card(Modifier.fillMaxWidth().clickable { onOpen(office) }) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(office.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                data?.let(::verdict) ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (data?.state?.lastPoll?.ok == false) {
                                Text("⚠ last poll failed", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(data?.state?.lastOk?.let { "${data.state.freeNow}" } ?: "–",
                                fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                data?.state?.nextFree?.let { "free · " + it.atZone(BERLIN).format(dayMonth) } ?: "free",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OfficeScreen(office: Office, modifier: Modifier) {
    val data = officeData(office) ?: return
    val insight = data.insight
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            office.authority + (data.state.trackingSince?.let { " · watched since " + it.atZone(BERLIN).format(dayMonth) } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BestChance(insight, data.watch)
        if (insight.byWeekday.values.any { it.known }) {
            BestTime(insight)
            Panel("All weekdays", "How long half of the slots stay free after release") {
                val rows = insight.byWeekday.keys.sorted().map { SurvivalRow(it, WEEKDAYS_SHORT[it - 1], insight.byWeekday[it]) }
                SurvivalBars(rows, insight.easiestDay()) { row -> detail(WEEKDAYS[row.key - 1], row.survival) }
            }
        }
        Releases(insight, data.state)
        FreeNow(data.freeDays)
    }
}

private fun detail(name: String, survival: Survival?): String {
    if (survival == null || survival.released == 0) return "$name: no released slot seen yet."
    val lasting = survival.medianHours?.let { "half were booked after ${durationText(it)}" }
        ?: "more than half still free after ${durationText(survival.observedHours)}"
    return "$name: ${survival.released} slots released while watched, ${survival.booked} booked; $lasting."
}

@Composable
private fun BestChance(insight: Insight, watch: Pair<Instant, Instant>?) {
    val easiest = insight.easiestDay()
    val hardest = insight.hardestDay()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Easiest day", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (easiest == null) {
                val known = insight.byWeekday.values.count { it.known }
                val best = insight.byWeekday.values.filter { !it.known }.maxOfOrNull { it.released } ?: 0
                Text("Still learning", fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                // Two measured weekdays make the first comparison.
                val progress = (known + best.toFloat() / Survival.MIN_RELEASED) / 2
                Meter(progress)
                Text(
                    "$known of 2 weekdays measured · ${insight.released} slots seen after their release",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                watch?.let { (start, end) ->
                    Text(
                        "Next release watch ${start.atZone(BERLIN).format(dayMonth)} ${clock(start)}–${clock(end)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                return@Column
            }
            val survival = insight.byWeekday.getValue(easiest)
            Text(WEEKDAYS[easiest - 1], fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                survival.medianHours?.let { "Half of its slots are still free ${durationText(it)} after release." }
                    ?: "More than half of its slots are still free ${durationText(survival.observedHours)} after release.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (hardest != null && hardest != easiest) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                Text(
                    "Hardest: ${WEEKDAYS[hardest - 1]}, half gone within ${durationText(insight.byWeekday.getValue(hardest).hours)}.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BestTime(insight: Insight) {
    val days = insight.byHour.keys.filter { it != ANY_DAY }.sorted()
    if (days.isEmpty()) return
    // The easiest weekday once its hours can be ranked, otherwise all weekdays pooled.
    val start = insight.easiestDay()?.takeIf { insight.easiestHour(it) != null } ?: ANY_DAY
    var day by remember(days) { mutableStateOf(start) }
    val hours = insight.byHour[day].orEmpty()
    val easiest = insight.easiestHour(day)
    val hardest = insight.hardestHour(day)
    val dayName = if (day == ANY_DAY) "any day" else WEEKDAYS[day - 1]
    val summary = when {
        easiest == null -> "Not enough released slots on $dayName yet."
        hardest != null && hardest != easiest ->
            "${minuteClock(easiest * 60)} lasts longest (${survivalText(hours.getValue(easiest))}); " +
                "${minuteClock(hardest * 60)} goes first (${durationText(hours.getValue(hardest).hours)})."
        else -> "${minuteClock(easiest * 60)} lasts longest (${survivalText(hours.getValue(easiest))})."
    }
    Panel("Best time", summary) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(ANY_DAY) + days).forEach { weekday ->
                FilterChip(selected = weekday == day, onClick = { day = weekday },
                    label = { Text(if (weekday == ANY_DAY) "All days" else WEEKDAYS_SHORT[weekday - 1]) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val rows = hours.keys.sorted().map { SurvivalRow(it, minuteClock(it * 60), hours[it]) }
        SurvivalBars(rows, easiest) { row -> detail(if (day == ANY_DAY) row.label else "${WEEKDAYS[day - 1]} ${row.label}", row.survival) }
    }
}

@Composable
private fun Releases(insight: Insight, state: OfficeState) {
    val last = insight.releases.lastOrNull()
    val window = Analysis.releaseWindow(insight.releases)
    val body = when {
        last == null -> "No new appointment day appeared since watching began."
        else -> {
            val before = if (Duration.between(last.before, last.seen) < Duration.ofMinutes(30)) {
                " (check before: ${clock(last.before)})"
            } else {
                ""
            }
            "Newest: ${last.day.format(dayMonth)}, appeared ${last.seen.atZone(BERLIN).format(dayMonth)}, ${clock(last.seen)}$before."
        }
    }
    Panel("New days", window?.let { "Usually released around ${minuteClock(it.second)}" } ?: "Release time not known yet") {
        Text(body, style = MaterialTheme.typography.bodyMedium)
        state.lastOk?.let {
            Text(
                "Last read ${clock(it.at)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun FreeNow(days: List<FreeDay>) {
    Panel("Free right now", if (days.isEmpty()) "Nothing free in the released days" else "${days.sumOf { it.appointments }} appointments on ${days.size} ${if (days.size == 1) "day" else "days"}") {
        days.forEachIndexed { index, free ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(free.day.format(dayMonth), Modifier.width(96.dp), fontWeight = FontWeight.Medium)
                Text("${clock(free.first)}–${clock(free.last)}", Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${free.appointments}", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Panel(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
