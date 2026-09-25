package io.github.tieo.visitorstracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.ln

/**
 * Chart colors from the reference palette: the chart chrome, one accent blue
 * for the answer, and a de-emphasis gray for everything it is compared with.
 */
data class ChartColors(
    val accent: Color,
    val context: Color,
    val grid: Color,
    val muted: Color,
    val ink: Color,
)

@Composable
fun chartColors(): ChartColors = if (isSystemInDarkTheme()) {
    ChartColors(Color(0xFF3987E5), Color(0xFF52514E), Color(0xFF2C2C2A), Color(0xFF898781), Color(0xFFFFFFFF))
} else {
    ChartColors(Color(0xFF2A78D6), Color(0xFFC3C2B7), Color(0xFFE1E0D9), Color(0xFF898781), Color(0xFF0B0B0B))
}

val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
val WEEKDAYS_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** A duration in the unit a person would say it in. */
fun durationText(hours: Double): String = when {
    hours < 1 -> "${maxOf(1, Math.round(hours * 60))} min"
    hours < 10 -> "%.1f h".format(Locale.ENGLISH, hours)
    hours < 36 -> "${Math.round(hours)} h"
    hours < 240 -> "%.1f d".format(Locale.ENGLISH, hours / 24)
    else -> "${Math.round(hours / 24)} d"
}

/** "3.1 d", or "> 2 d" while more than half of the slots are still free. */
fun survivalText(survival: Survival): String =
    survival.medianHours?.let(::durationText) ?: "> ${durationText(survival.observedHours)}"

/** Hours on the log axis of [SurvivalBars]: ten minutes to two weeks. */
private const val AXIS_MIN = 1.0 / 6
private const val AXIS_MAX = 24.0 * 14
private val TICKS = listOf(1.0 / 6 to "10 min", 1.0 to "1 h", 6.0 to "6 h", 24.0 to "1 d", 24.0 * 7 to "1 wk")

/** Room kept right of the axis for the value label at the end of the longest bar. */
private val LABEL_ROOM = 44.dp

private fun axisFraction(hours: Double): Float {
    val clamped = hours.coerceIn(AXIS_MIN, AXIS_MAX)
    return (ln(clamped / AXIS_MIN) / ln(AXIS_MAX / AXIS_MIN)).toFloat()
}

data class SurvivalRow(val key: Int, val label: String, val survival: Survival?)

/**
 * One horizontal bar per row: how long half of the row's slots stayed free
 * after release, on a log axis so minutes and days both read. The best row is
 * the accent; the others are context. Tapping a row shows its numbers.
 */
@Composable
fun SurvivalBars(rows: List<SurvivalRow>, highlight: Int?, detail: (SurvivalRow) -> String) {
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    var selected by remember(rows) { mutableStateOf<Int?>(null) }
    val valueStyle = TextStyle(fontSize = 12.sp, color = colors.ink, fontWeight = FontWeight.Medium)
    val learningStyle = TextStyle(fontSize = 12.sp, color = colors.muted)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEach { row ->
            val survival = row.survival
            Row(
                Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (selected == row.key) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { selected = if (selected == row.key) null else row.key },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.label, Modifier.width(52.dp).padding(start = 6.dp), fontSize = 13.sp,
                    fontWeight = if (row.key == highlight) FontWeight.SemiBold else FontWeight.Normal,
                )
                Canvas(Modifier.weight(1f).height(34.dp)) {
                    gridLines(colors.grid)
                    if (survival == null || !survival.known) {
                        val released = survival?.released ?: 0
                        val text = measurer.measure("$released of ${survival?.minimum ?: Survival.MIN_RELEASED} seen", learningStyle)
                        drawText(text, topLeft = Offset(6.dp.toPx(), (size.height - text.size.height) / 2))
                        return@Canvas
                    }
                    val label = measurer.measure(survivalText(survival), valueStyle)
                    val axis = size.width - LABEL_ROOM.toPx()
                    val length = maxOf(6.dp.toPx(), axis * axisFraction(survival.hours))
                    val thickness = 16.dp.toPx()
                    val top = (size.height - thickness) / 2
                    val color = if (row.key == highlight) colors.accent else colors.context
                    val radius = minOf(4.dp.toPx(), length / 2)
                    // Square at the baseline, rounded at the data end.
                    drawRect(color, Offset(0f, top), Size(length - radius, thickness))
                    drawRoundRect(color, Offset(length - 2 * radius, top), Size(2 * radius, thickness), CornerRadius(radius))
                    drawText(label, topLeft = Offset(length + 6.dp.toPx(), (size.height - label.size.height) / 2))
                }
            }
        }
        Row {
            Box(Modifier.width(52.dp))
            Canvas(Modifier.weight(1f).height(16.dp)) {
                val tickStyle = TextStyle(fontSize = 10.sp, color = colors.muted)
                val axis = size.width - LABEL_ROOM.toPx()
                TICKS.forEach { (hours, name) ->
                    val text = measurer.measure(name, tickStyle)
                    val x = axis * axisFraction(hours)
                    drawText(text, topLeft = Offset((x - text.size.width / 2).coerceIn(0f, size.width - text.size.width), 0f))
                }
            }
        }
    }
    val chosen = rows.firstOrNull { it.key == selected }
    Text(
        chosen?.let(detail).orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

private fun DrawScope.gridLines(color: Color) {
    val axis = size.width - LABEL_ROOM.toPx()
    TICKS.forEach { (hours, _) ->
        val x = axis * axisFraction(hours)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
}

/** How much of what is needed has been observed, as a same-hue track and fill. */
@Composable
fun Meter(fraction: Float) {
    val colors = chartColors()
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        drawRoundRect(colors.accent.copy(alpha = 0.18f), cornerRadius = CornerRadius(3.dp.toPx()))
        drawRoundRect(
            colors.accent, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
}
