package pl.kreska.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

fun niceMax(v: Float): Float {
    if (v <= 0f) return 1f
    val p = 10.0.pow(floor(log10(v.toDouble()))).toFloat()
    val m = v / p
    val n = when {
        m <= 1f -> 1f
        m <= 2f -> 2f
        m <= 2.5f -> 2.5f
        m <= 5f -> 5f
        else -> 10f
    }
    return n * p
}

/** Słupki dzienne: siatka 0 / połowa / max, etykiety dat co kilka dni. Dzisiejszy słupek jaśniejszy (dzień trwa). */
@Composable
fun BarChart(days: List<LocalDate>, values: List<Float>, color: Color, format: (Float) -> String) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val today = LocalDate.now()
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val left = 46.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left
        val h = size.height - top - bottom
        val yMax = niceMax(values.maxOrNull() ?: 0f)

        for (f in listOf(0f, 0.5f, 1f)) {
            val y = top + h - h * f
            drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
            val t = measurer.measure(format(yMax * f), labelStyle)
            drawText(t, topLeft = Offset(left - 6.dp.toPx() - t.size.width, y - t.size.height / 2f))
        }

        val n = values.size
        if (n == 0) return@Canvas
        val bw = w / n
        val gap = when {
            n > 60 -> 0.5f
            n > 20 -> 1.5.dp.toPx()
            else -> minOf(10.dp.toPx(), bw * 0.28f)
        }
        val step = if (n <= 8) 1 else ceil(n / 6.0).toInt()
        values.forEachIndexed { i, value ->
            if (value > 0f) {
                val bh = maxOf(1.5f, h * value / yMax)
                drawRect(
                    color = if (days[i] == today) color.copy(alpha = 0.55f) else color,
                    topLeft = Offset(left + i * bw + gap / 2f, top + h - bh),
                    size = Size(maxOf(0.6f, bw - gap), bh),
                )
            }
            if ((n - 1 - i) % step == 0) {
                val d = days[i]
                val label = if (n <= 8) Dates.weekday(d) else "${d.dayOfMonth}.${d.monthValue.toString().padStart(2, '0')}"
                val t = measurer.measure(label, labelStyle)
                val x = (left + i * bw + bw / 2f - t.size.width / 2f).coerceIn(left, size.width - t.size.width)
                drawText(t, topLeft = Offset(x, size.height - t.size.height))
            }
        }
    }
}

/** Rozkład kliknięć w ciągu doby (0–23). */
@Composable
fun HourChart(hours: IntArray, color: Color) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val top = 6.dp.toPx()
        val bottom = 20.dp.toPx()
        val h = size.height - top - bottom
        val bw = size.width / 24f
        val yMax = maxOf(1, hours.maxOrNull() ?: 0).toFloat()
        drawLine(grid, Offset(0f, top + h), Offset(size.width, top + h), strokeWidth = 1f)
        for (i in 0 until 24) {
            val v = hours[i]
            if (v > 0) {
                val bh = maxOf(1.5f, h * v / yMax)
                drawRect(color, Offset(i * bw + 2f, top + h - bh), Size(bw - 4f, bh))
            }
            if (i % 3 == 0) {
                val t = measurer.measure("$i", labelStyle)
                drawText(t, topLeft = Offset(i * bw + bw / 2f - t.size.width / 2f, size.height - t.size.height))
            }
        }
    }
}

/** Kreski w grupach po pięć — tak jak liczy się na kartce. */
@Composable
fun Tally(n: Int, color: Color) {
    if (n <= 0) return
    val groups = (n + 4) / 5
    Canvas(Modifier.height(20.dp).fillMaxWidth()) {
        val gw = 30.dp.toPx()
        val sw = 1.8.dp.toPx()
        for (g in 0 until groups) {
            val x0 = g * gw
            if (x0 + 22.dp.toPx() > size.width) break
            val k = minOf(5, n - g * 5)
            for (i in 0 until minOf(k, 4)) {
                val x = x0 + (3 + i * 5).dp.toPx()
                drawLine(color, Offset(x, 2.dp.toPx()), Offset(x, 18.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
            }
            if (k == 5) {
                drawLine(color, Offset(x0, 15.dp.toPx()), Offset(x0 + 21.dp.toPx(), 5.dp.toPx()), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}
