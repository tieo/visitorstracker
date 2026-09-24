package io.github.tieo.visitorstracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tieo.visitorstracker.source.Flexappoint.BERLIN
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Chart colors: the reference palette's chart chrome, one series blue, and a
 * five-step ordinal blue ramp for the heatmap, validated as an ordinal ramp in
 * both modes (light: darker is faster; dark: lighter is faster).
 */
data class ChartColors(
    val series: Color,
    val wash: Color,
    val grid: Color,
    val axis: Color,
    val muted: Color,
    val heat: List<Color>,
)

@Composable
fun chartColors(): ChartColors = if (isSystemInDarkTheme()) {
    ChartColors(Color(0xFF3987E5), Color(0x1A3987E5), Color(0xFF2C2C2A), Color(0xFF383835), Color(0xFF898781),
        listOf(Color(0xFF9EC5F4), Color(0xFF6DA7EC), Color(0xFF3987E5), Color(0xFF256ABF), Color(0xFF184F95)))
} else {
    ChartColors(Color(0xFF2A78D6), Color(0x1A2A78D6), Color(0xFFE1E0D9), Color(0xFFC3C2B7), Color(0xFF898781),
        listOf(Color(0xFF104281), Color(0xFF1C5CAB), Color(0xFF2A78D6), Color(0xFF5598E7), Color(0xFF86B6EF)))
}

/** Heatmap classes, fastest first; the last is "stays free". */
private val CLASSES = listOf("under 1 h" to 1.0, "1 to 12 h" to 12.0, "12 h to 3 days" to 72.0, "over 3 days" to null, "stays free" to null)

/** A cell counts as "stays free" once its slots were watched this long without half of them going. */
private const val STAYS_AFTER_HOURS = 24.0

fun classify(cell: Cell): Int? {
    val median = cell.median
    if (median == null) return if (cell.measured > 0 && cell.observed >= STAYS_AFTER_HOURS) 4 else null
    return CLASSES.indexOfFirst { (_, limit) -> limit == null || median < limit }
}

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val dayLabel = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

fun hoursText(hours: Double): String = when {
    hours < 1 -> "${maxOf(1, Math.round(hours * 60))} min"
    hours < 48 -> "%.1f h".format(Locale.ENGLISH, hours)
    else -> "%.1f days".format(Locale.ENGLISH, hours / 24)
}

private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

private fun cellText(key: CellKey, cell: Cell): String {
    val speed = when {
        cell.median != null -> "half booked after ${hoursText(cell.median)}"
        classify(cell) == 4 -> "less than half booked after ${hoursText(cell.observed)}"
        cell.measured > 0 -> "watched for ${hoursText(cell.observed)} so far"
        else -> "release not observed"
    }
    return "${WEEKDAYS[key.weekday - 1]} ${clock(key.minute)}: $speed · start times: " +
        "${cell.booked} booked, ${cell.free} free, ${cell.expired} expired"
}

@Composable
fun Heatmap(cells: Map<CellKey, Cell>) {
    if (cells.isEmpty()) {
        Text("No slots seen yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val colors = chartColors()
    val days = cells.keys.map { it.weekday }.distinct().sorted()
    val first = cells.keys.minOf { it.minute }
    val last = cells.keys.maxOf { it.minute }
    val columns = (first..last step BUCKET_MINUTES).toList()
    var selected by remember { mutableStateOf<CellKey?>(null) }
    val cell = 28.dp
    val gap = 2.dp

    Row(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            days.forEach { day ->
                Box(Modifier.height(cell).width(34.dp), contentAlignment = Alignment.CenterStart) {
                    Text(WEEKDAYS[day - 1], fontSize = 11.sp, color = colors.muted)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.height(16.dp)) {
                columns.forEach { minute ->
                    Box(Modifier.width(cell)) {
                        if (minute % 60 == 0) Text(clock(minute), fontSize = 10.sp, color = colors.muted, softWrap = false)
                    }
                }
            }
            days.forEach { day ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    columns.forEach { minute ->
                        val key = CellKey(day, minute)
                        val value = cells[key]
                        val shape = RoundedCornerShape(4.dp)
                        var modifier = Modifier.size(cell)
                        if (value != null) {
                            val kind = classify(value)
                            modifier = modifier.clickable { selected = key }.let {
                                if (kind != null) it.background(colors.heat[kind], shape) else it.border(1.dp, colors.axis, shape)
                            }
                            if (selected == key) modifier = modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape)
                        }
                        Box(modifier)
                    }
                }
            }
        }
    }
    Readout(selected?.let { key -> cells[key]?.let { cellText(key, it) } })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CLASSES.forEachIndexed { index, (label, _) -> LegendItem(label) { Modifier.background(colors.heat[index], it) } }
        LegendItem("not watched long enough") { Modifier.border(1.dp, colors.axis, it) }
    }
    TableToggle {
        cells.toSortedMap(compareBy({ it.weekday }, { it.minute })).forEach { (key, value) ->
            TableRow(
                "${WEEKDAYS[key.weekday - 1]} ${clock(key.minute)}",
                value.median?.let(::hoursText) ?: "–",
                "${value.booked} booked / ${value.free} free / ${value.expired} expired",
            )
        }
    }
}

@Composable
private fun LegendItem(label: String, swatch: (RoundedCornerShape) -> Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(swatch(RoundedCornerShape(3.dp)).size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Axis top and tick step: at most five clean steps covering [value]. */
fun niceScale(value: Int): Pair<Int, Int> {
    val top = maxOf(value, 1)
    val step = listOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000).firstOrNull { (top + it - 1) / it <= 5 } ?: 1000
    return ((top + step - 1) / step) * step to step
}

@Composable
fun DayBars(days: List<Pair<LocalDate, Int>>) {
    if (days.isEmpty()) {
        Text("No free appointment right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val (peak, step) = niceScale(days.maxOf { it.second })
    var selected by remember { mutableStateOf<Int?>(null) }
    val slot = 40.dp
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.muted)

    Row {
        Canvas(Modifier.width(30.dp).height(190.dp)) {
            val plot = size.height - 36.dp.toPx()
            for (tick in 0..peak step step) {
                val y = plot - plot * tick / peak
                val text = measurer.measure(tick.toString(), labelStyle)
                drawText(text, topLeft = Offset(size.width - text.size.width - 4.dp.toPx(), y - text.size.height / 2))
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Canvas(
                Modifier.width(slot * days.size).height(190.dp).pointerInput(days) {
                    detectTapGestures { offset -> selected = (offset.x / slot.toPx()).toInt().coerceIn(days.indices) }
                },
            ) {
                val plot = size.height - 36.dp.toPx()
                val bar = 24.dp.toPx()
                for (tick in 0..peak step step) {
                    val y = plot - plot * tick / peak
                    drawLine(colors.grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                days.forEachIndexed { index, (day, count) ->
                    val x = index * slot.toPx() + (slot.toPx() - bar) / 2
                    val tall = plot * count / peak
                    val radius = minOf(4.dp.toPx(), tall)
                    val path = Path().apply {
                        moveTo(x, plot)
                        lineTo(x, plot - tall + radius)
                        quadraticTo(x, plot - tall, x + radius, plot - tall)
                        lineTo(x + bar - radius, plot - tall)
                        quadraticTo(x + bar, plot - tall, x + bar, plot - tall + radius)
                        lineTo(x + bar, plot)
                        close()
                    }
                    drawPath(path, if (selected == null || selected == index) colors.series else colors.series.copy(alpha = 0.45f))
                    val weekday = measurer.measure(WEEKDAYS[day.dayOfWeek.value - 1], labelStyle)
                    val date = measurer.measure(day.format(dayLabel), labelStyle)
                    val center = x + bar / 2
                    drawText(weekday, topLeft = Offset(center - weekday.size.width / 2, plot + 4.dp.toPx()))
                    drawText(date, topLeft = Offset(center - date.size.width / 2, plot + 18.dp.toPx()))
                }
                drawLine(colors.axis, Offset(0f, plot), Offset(size.width, plot), 1.dp.toPx())
            }
        }
    }
    Readout(selected?.let { days[it] }?.let { (day, count) -> "${WEEKDAYS[day.dayOfWeek.value - 1]} ${day.format(dayLabel)}: $count free" })
    TableToggle {
        days.forEach { (day, count) -> TableRow("${WEEKDAYS[day.dayOfWeek.value - 1]} ${day.format(dayLabel)}", "$count") }
    }
}

@Composable
fun Timeline(points: List<Pair<Instant, Int>>) {
    if (points.size < 2) {
        Text("The history appears from the second poll on.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val colors = chartColors()
    val surface = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val (peak, step) = niceScale(points.maxOf { it.second })
    val start = points.first().first
    val end = maxOf(points.last().first, start.plusSeconds(3600))
    val span = Duration.between(start, end).toMillis().toFloat()
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.muted)
    val left = 34.dp

    val leftPx = with(androidx.compose.ui.platform.LocalDensity.current) { left.toPx() }
    fun nearest(x: Float, width: Float): Int {
        val at = (x - leftPx) / (width - leftPx) * span
        return points.indices.minBy { index -> kotlin.math.abs(Duration.between(start, points[index].first).toMillis() - at) }
    }

    Canvas(
        Modifier.fillMaxWidth().height(200.dp)
            .pointerInput(points) { detectTapGestures { selected = nearest(it.x, size.width.toFloat()) } }
            .pointerInput(points) { detectDragGestures { change, _ -> selected = nearest(change.position.x, size.width.toFloat()) } },
    ) {
        val plotLeft = left.toPx()
        val plotHeight = size.height - 22.dp.toPx()
        val plotWidth = size.width - plotLeft
        fun x(at: Instant) = plotLeft + plotWidth * Duration.between(start, at).toMillis() / span
        fun y(value: Int) = plotHeight - plotHeight * value / peak

        for (tick in 0..peak step step) {
            drawLine(colors.grid, Offset(plotLeft, y(tick)), Offset(size.width, y(tick)), 1.dp.toPx())
            val text = measurer.measure(tick.toString(), labelStyle)
            drawText(text, topLeft = Offset(plotLeft - text.size.width - 4.dp.toPx(), y(tick) - text.size.height / 2))
        }
        // Day boundaries mark the axis; a span of under two days gets hour ticks.
        val hours = span / 3_600_000f
        val tickHours = if (hours > 48) 24 else listOf(1, 2, 3, 6, 12).first { hours / it <= 6 }
        var moment = start.atZone(BERLIN).truncatedTo(ChronoUnit.HOURS)
        moment = moment.withHour(if (tickHours == 24) 0 else moment.hour - moment.hour % tickHours).plusHours(tickHours.toLong())
        while (moment.toInstant().isBefore(end)) {
            val tx = x(moment.toInstant())
            drawLine(colors.grid, Offset(tx, 0f), Offset(tx, plotHeight), 1.dp.toPx())
            val label = if (moment.hour == 0) "${WEEKDAYS[moment.dayOfWeek.value - 1]} ${moment.format(dayLabel)}"
            else "%02d:00".format(moment.hour)
            val text = measurer.measure(label, labelStyle)
            drawText(text, topLeft = Offset(tx - text.size.width / 2, plotHeight + 6.dp.toPx()))
            moment = moment.plusHours(tickHours.toLong())
        }
        val line = Path()
        points.forEachIndexed { index, (at, value) ->
            if (index == 0) line.moveTo(x(at), y(value)) else line.lineTo(x(at), y(value))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(points.last().first), plotHeight)
            lineTo(x(points.first().first), plotHeight)
            close()
        }
        drawPath(area, colors.wash)
        drawPath(line, colors.series, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(colors.axis, Offset(plotLeft, plotHeight), Offset(size.width, plotHeight), 1.dp.toPx())
        selected?.let { index ->
            val (at, value) = points[index]
            drawLine(colors.axis, Offset(x(at), 0f), Offset(x(at), plotHeight), 1.dp.toPx())
            drawCircle(surface, 6.dp.toPx(), Offset(x(at), y(value)))
            drawCircle(colors.series, 4.dp.toPx(), Offset(x(at), y(value)))
        }
    }
    Readout(selected?.let { points[it] }?.let { (at, value) -> "$value free at ${Collector.format(at)}" })
    TableToggle {
        points.asReversed().forEach { (at, value) -> TableRow(Collector.format(at), "$value") }
    }
}

/** The selected mark's numbers; the line keeps its height while nothing is selected. */
@Composable
private fun Readout(text: String?) {
    Text(
        text.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        minLines = 1,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun TableToggle(rows: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(
        (if (open) "▾ " else "▸ ") + "Table",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp).clickable { open = !open },
    )
    if (open) Column(Modifier.padding(top = 4.dp)) { rows() }
}

@Composable
private fun TableRow(vararg cells: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        cells.forEachIndexed { index, text ->
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = if (index == 0) Modifier.width(110.dp) else Modifier)
        }
    }
}
