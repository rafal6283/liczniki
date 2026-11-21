package pl.kreska.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val MONTHS_SHORT = listOf("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")

/**
 * Kalendarz okresu w kratkach (kolumna = tydzień, wiersz = dzień tygodnia), kolor wg wartości dnia.
 * Kratki wypełniają się falą od najstarszego tygodnia; dotknięcie lub przeciąganie pokazuje dzień,
 * a przycisk pod spodem otwiera jego wpisy. Dni z osiągniętym celem mają kropkę.
 */
@Composable
fun CalendarGrid(days: List<LocalDate>, values: List<Float>, color: Color, goal: Float?, onDay: (LocalDate) -> Unit) {
    if (days.isEmpty() || values.size != days.size) return
    val offset = days.first().dayOfWeek.value - 1
    val weeks = (offset + days.size + 6) / 7
    var maxV = 0f
    for (v in values) if (v > maxV) maxV = v
    val key = days.first() to values.hashCode()
    val p = entrance(key, 1000)
    val sel = rememberSelection(key)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.inverseOnSurface)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val ring = MaterialTheme.colorScheme.onSurface
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val today = LocalDate.now()
    val density = LocalDensity.current
    val left = with(density) { 22.dp.toPx() }
    val top = with(density) { 16.dp.toPx() }
    val maxCell = with(density) { 26.dp.toPx() }

    Column {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wPx = with(density) { maxWidth.toPx() }
            val cell = minOf((wPx - left) / weeks, maxCell)
            val hDp = with(density) { (top + cell * 7 + 4.dp.toPx()).toDp() }
            Canvas(
                Modifier.fillMaxWidth().height(hDp)
                    .chartTouch(sel, days.size) { o, _ ->
                        val col = ((o.x - left) / cell).toInt()
                        val row = ((o.y - top) / cell).toInt()
                        val i = col * 7 + row - offset
                        if (o.x < left || o.y < top || row !in 0 until 7 || i !in days.indices) -1 else i
                    }
            ) {
                val pad = maxOf(1f, cell * 0.08f)
                for (r in 0 until 7 step 2) {
                    val t = measurer.measure(WEEKDAYS[r], labelStyle)
                    drawText(t, topLeft = Offset(0f, top + r * cell + cell / 2f - t.size.height / 2f))
                }
                var lastMonthX = -1000f
                for (i in days.indices) {
                    val d = days[i]
                    val col = (i + offset) / 7
                    val row = (i + offset) % 7
                    val x = left + col * cell
                    val y = top + row * cell
                    if ((d.dayOfMonth == 1 || i == 0) && x - lastMonthX > cell * 2.5f) {
                        drawText(measurer.measure(MONTHS_SHORT[d.monthValue - 1], labelStyle), topLeft = Offset(x, 0f))
                        lastMonthX = x
                    }
                    // fala: tydzień po tygodniu
                    val q = (p * 1.5f - col.toFloat() / maxOf(1, weeks - 1) * 0.5f).coerceIn(0f, 1f)
                    val v = values[i]
                    val target = if (v <= 0f || maxV <= 0f) empty else color.copy(alpha = 0.18f + 0.82f * (v / maxV))
                    val c = if (target == empty) empty else lerp(empty, target, q)
                    val shrink = (1f - q) * cell * 0.3f
                    val a = barAlpha(i, sel, if (d.isAfter(today)) 0.3f else 1f)
                    drawRoundRect(
                        c.copy(alpha = c.alpha * a),
                        Offset(x + pad + shrink, y + pad + shrink),
                        Size(maxOf(0f, cell - 2 * pad - 2 * shrink), maxOf(0f, cell - 2 * pad - 2 * shrink)),
                        CornerRadius(cell * 0.18f, cell * 0.18f),
                    )
                    if (goal != null && goal > 0f && v >= goal && q > 0.9f) {
                        drawCircle(dotOn(c), radius = cell * 0.09f, center = Offset(x + cell / 2f, y + cell / 2f))
                    }
                }
                val s = sel.last
                val tipA = sel.alpha.value
                if (s in days.indices && tipA > 0f) {
                    val col = (s + offset) / 7
                    val row = (s + offset) % 7
                    drawRoundRect(ring.copy(alpha = tipA), Offset(left + col * cell, top + row * cell), Size(cell, cell),
                        CornerRadius(cell * 0.2f, cell * 0.2f), style = Stroke(1.5.dp.toPx()))
                    tooltip(measurer, "${Dates.day(days[s])} · ${Num.fmt(values[s])}", left + col * cell + cell / 2f, top + row * cell, tipStyle, tipBg, tipA)
                }
            }
        }
        val s = sel.index
        if (s in days.indices) {
            TextButton(onClick = { onDay(days[s]) }, contentPadding = PaddingValues(0.dp)) { Text("Wpisy z ${Dates.day(days[s])} →") }
        } else {
            Text(
                "Dotknij dnia, aby zobaczyć wartość i wpisy." + if (goal != null && goal > 0f) " Kropka – osiągnięty cel." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Kontrastowa kropka na kratce: biała na ciemnym, ciemna na jasnym. */
private fun dotOn(c: Color): Color =
    if (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue > 0.6f || c.alpha < 0.5f) Color(0xCC000000) else Color(0xE6FFFFFF)
