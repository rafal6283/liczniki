package pl.kreska.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Słupki dzienne: siatka 0 / połowa / max, etykiety dat co kilka dni. Dzisiejszy słupek jaśniejszy (dzień trwa).
 * Słupki wyrastają kaskadą, skala osi płynnie się dopasowuje, a dotknięcie lub przeciągnięcie palcem pokazuje wartość dnia.
 */
@Composable
fun BarChart(days: List<LocalDate>, values: List<Float>, color: Color, format: (Float) -> String) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.inverseOnSurface)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val today = LocalDate.now()
    val anim = animatedSeries(values)
    val yMax by animateFloatAsState(niceMax(values.maxOrNull() ?: 0f), spring(stiffness = Spring.StiffnessLow), label = "yMax")
    val sel = rememberSelection(days.firstOrNull() to values.size)
    val left = with(LocalDensity.current) { 46.dp.toPx() }

    Canvas(
        Modifier.fillMaxWidth().height(170.dp)
            .chartTouch(sel, values.size) { o, width ->
                val n = values.size
                if (n == 0 || width <= left || o.x < left - 8f) -1 else ((o.x - left) / ((width - left) / n)).toInt().coerceIn(0, n - 1)
            }
    ) {
        val top = 8.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left
        val h = size.height - top - bottom

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
        val radius = if (bw - gap > 6.dp.toPx()) 3.dp.toPx() else 0f
        val step = if (n <= 8) 1 else ceil(n / 6.0).toInt()
        for (i in 0 until n) {
            val value = anim[i]
            if (value > 0f) {
                val bh = maxOf(1.5f, h * value / yMax).coerceAtMost(h)
                val base = if (days[i] == today) 0.55f else 1f
                drawRoundRect(
                    color = color.copy(alpha = barAlpha(i, sel, base)),
                    topLeft = Offset(left + i * bw + gap / 2f, top + h - bh),
                    size = Size(maxOf(0.6f, bw - gap), bh),
                    cornerRadius = CornerRadius(radius, radius),
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

        val s = sel.last
        if (s in 0 until n) {
            val v = values[s]
            val cx = left + s * bw + bw / 2f
            val barTop = top + h - maxOf(1.5f, h * anim[s] / yMax).coerceAtMost(h)
            tooltip(
                measurer, "${Dates.weekday(days[s])} ${Dates.day(days[s])} · ${format(v)}",
                cx, if (v > 0f) barTop else top + h, tipStyle, tipBg, sel.alpha.value,
                guide = grid, guideBottom = top + h,
            )
        }
    }
}

/** Rozkład kliknięć w ciągu doby (0–23). */
@Composable
fun HourChart(hours: IntArray, color: Color) {
    LabeledBars(
        labels = (0 until 24).map { "$it" },
        values = hours.map { it.toFloat() },
        color = color,
        format = { Num.fmt(it) },
        labelEvery = 3,
        chartHeight = 110.dp,
        tipLabels = (0 until 24).map { "$it:00" },
    )
}

/**
 * Kreski w grupach po pięć — tak jak liczy się na kartce.
 * Nowa kreska (i przekreślenie piątki) rysuje się płynnie, jakby długopisem.
 */
@Composable
fun Tally(n: Int, color: Color) {
    if (n <= 0) return
    val shown by animateFloatAsState(n.toFloat(), spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow), label = "tally")
    val groups = (n + 4) / 5
    Canvas(Modifier.height(20.dp).fillMaxWidth()) {
        val gw = 30.dp.toPx()
        val sw = 1.8.dp.toPx()
        for (g in 0 until groups) {
            val x0 = g * gw
            if (x0 + 22.dp.toPx() > size.width) break
            for (i in 0 until 5) {
                val k = g * 5 + i // indeks kreski (0..)
                val p = (shown - k).coerceIn(0f, 1f)
                if (p <= 0f) break
                if (i < 4) {
                    val x = x0 + (3 + i * 5).dp.toPx()
                    val y0 = 2.dp.toPx()
                    val y1 = 18.dp.toPx()
                    drawLine(color, Offset(x, y0), Offset(x, y0 + (y1 - y0) * p), strokeWidth = sw, cap = StrokeCap.Round)
                } else {
                    val a = Offset(x0, 15.dp.toPx())
                    val b = Offset(x0 + 21.dp.toPx(), 5.dp.toPx())
                    drawLine(color, a, Offset(a.x + (b.x - a.x) * p, a.y + (b.y - a.y) * p), strokeWidth = sw, cap = StrokeCap.Round)
                }
            }
        }
    }
}
