package pl.kreska.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/*
 * Migotanie przedsionków z innych aplikacji na wykresach zdarzenia AFIB:
 *  - Polar EKG: epizody AF rozpoznane w EKG (czerwone, pełne, z białą obwódką),
 *  - Rytm Snu: nieregularny rytm z odstępów RR opaski w nocy (fioletowe, kreskowane).
 * Wpisy z Kreski zostają w kolorze zdarzenia, więc dane z aplikacji od razu się odróżniają.
 */

object AfColors {
    @Composable
    fun ecg(): Color = if (isSystemInDarkTheme()) Color(0xFFFF6B6B) else Color(0xFFD32F2F)

    @Composable
    fun night(): Color = if (isSystemInDarkTheme()) Color(0xFFB39DDB) else Color(0xFF6A4FB3)
}

/** Minuty z przedziałów przypadające na każdy dzień. */
fun dailyMinutes(spans: List<Span>, days: List<LocalDate>): List<Float> = days.map { d ->
    val day = listOf(Span(Dates.startOf(d), Dates.startOf(d.plusDays(1))))
    Spans.total(Spans.intersect(spans, day)) / 60_000f
}

/** Dodatkowa seria na wykresie słupkowym (np. AF z Polar EKG). */
class BarExtra(val label: String, val values: List<Float>, val color: Color, val dashed: Boolean = false)

/** Prostokąt w kreski – wyróżnia dane z Rytmu Snu. */
fun DrawScope.dashedRect(color: Color, topLeft: Offset, size: Size) {
    val step = 4.dp.toPx()
    val dash = 2.5.dp.toPx()
    var x = topLeft.x
    while (x < topLeft.x + size.width) {
        val w = minOf(dash, topLeft.x + size.width - x)
        drawRect(color, Offset(x, topLeft.y), Size(maxOf(0.8f, w), size.height))
        x += step
    }
    drawRect(color, topLeft, size, style = Stroke(1.dp.toPx()))
}

@Composable
fun AfLegend(trackerName: String, trackerColor: Color, ecg: Boolean, night: Boolean) {
    val e = AfColors.ecg()
    val n = AfColors.night()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        LegendDot(trackerColor, false)
        Text(" $trackerName (Kreska)", style = MaterialTheme.typography.labelSmall)
        if (ecg) {
            Spacer(Modifier.width(10.dp))
            LegendDot(e, false, outlined = true)
            Text(" Polar EKG", style = MaterialTheme.typography.labelSmall, color = e, fontWeight = FontWeight.Bold)
        }
        if (night) {
            Spacer(Modifier.width(10.dp))
            LegendDot(n, true)
            Text(" Rytm Snu", style = MaterialTheme.typography.labelSmall, color = n, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LegendDot(c: Color, dashed: Boolean, outlined: Boolean = false) {
    if (dashed) {
        Canvas(Modifier.size(12.dp, 10.dp)) { dashedRect(c, Offset.Zero, size) }
    } else {
        Box(
            Modifier.size(12.dp, 10.dp).background(c, RoundedCornerShape(2.dp))
                .then(if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp)) else Modifier)
        )
    }
}

private data class AfItem(val source: Int, val span: Span) // 0 = Kreska, 1 = Polar EKG, 2 = Rytm Snu

/**
 * Oś czasu AF: każdy dzień to wiersz 0–24 h z trzema pasami – wpisy w Kresce, AF w Polar EKG, nieregularny rytm z Rytmu Snu.
 * Dotknięcie wiersza pokazuje pod wykresem start, koniec i czas trwania każdego epizodu z tego dnia.
 */
@Composable
fun AfTimeline(days: List<LocalDate>, kreska: List<Span>, ecg: List<Span>, night: List<Span>, trackerName: String, color: Color) {
    val ecgC = AfColors.ecg()
    val nightC = AfColors.night()
    val surface = MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val selBg = MaterialTheme.colorScheme.surfaceVariant
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val haptic = LocalHapticFeedback.current

    // dni z czymkolwiek, najnowsze na górze (maks. 21)
    val rows = remember(days, kreska, ecg, night) {
        days.reversed().filter { d ->
            val r = listOf(Span(Dates.startOf(d), Dates.startOf(d.plusDays(1))))
            listOf(kreska, ecg, night).any { Spans.intersect(it, r).isNotEmpty() }
        }.take(21)
    }
    if (rows.isEmpty()) {
        Text("Brak AFIB w Kresce ani migotania z aplikacji w tym okresie.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // domyślnie najnowszy dzień z danymi z aplikacji
    val firstExt = rows.indexOfFirst { d ->
        val r = listOf(Span(Dates.startOf(d), Dates.startOf(d.plusDays(1))))
        Spans.intersect(ecg, r).isNotEmpty() || Spans.intersect(night, r).isNotEmpty()
    }
    var sel by remember(rows) { mutableIntStateOf(maxOf(0, firstExt)) }
    val rowH = 34.dp
    val head = 16.dp
    val leftDp = 64.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    val headPx = with(LocalDensity.current) { head.toPx() }

    Canvas(
        Modifier.fillMaxWidth().height(head + rowH * rows.size)
            .pointerInput(rows) {
                detectTapGestures { o ->
                    val i = ((o.y - headPx) / rowPx).toInt()
                    if (i in rows.indices && i != sel) {
                        sel = i
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
    ) {
        val left = leftDp.toPx()
        val w = size.width - left
        for (h in listOf(0, 6, 12, 18, 24)) {
            val x = left + w * h / 24f
            drawLine(grid, Offset(x, headPx - 2.dp.toPx()), Offset(x, size.height), strokeWidth = 1f)
            val t = measurer.measure("$h", label)
            drawText(t, topLeft = Offset((x - t.size.width / 2f).coerceIn(left, size.width - t.size.width), 0f))
        }
        rows.forEachIndexed { i, d ->
            val y0 = headPx + i * rowPx
            if (i == sel) drawRoundRect(selBg, Offset(0f, y0 + 1.dp.toPx()), Size(size.width, rowPx - 2.dp.toPx()), CornerRadius(6.dp.toPx()))
            val lt = measurer.measure("${Dates.weekday(d)} ${d.dayOfMonth}.${d.monthValue.toString().padStart(2, '0')}", label)
            drawText(lt, topLeft = Offset(4.dp.toPx(), y0 + (rowPx - lt.size.height) / 2f))
            val ds = Dates.startOf(d)
            val de = Dates.startOf(d.plusDays(1))
            fun x(t: Long) = left + w * ((t - ds).toFloat() / (de - ds)).coerceIn(0f, 1f)
            val lane = 7.dp.toPx()
            val gap = 2.dp.toPx()
            val top = y0 + (rowPx - 3 * lane - 2 * gap) / 2f
            // tło pasa
            drawRoundRect(grid.copy(alpha = 0.35f), Offset(left, top), Size(w, 3 * lane + 2 * gap), CornerRadius(3.dp.toPx()))
            for (s in Spans.intersect(kreska, listOf(Span(ds, de)))) {
                drawRoundRect(color, Offset(x(s.start), top), Size(maxOf(2.dp.toPx(), x(s.end) - x(s.start)), lane), CornerRadius(2.dp.toPx()))
            }
            for (s in Spans.intersect(ecg, listOf(Span(ds, de)))) {
                val o = Offset(x(s.start), top + lane + gap)
                val sz = Size(maxOf(3.dp.toPx(), x(s.end) - x(s.start)), lane)
                drawRect(ecgC, o, sz)
                drawRect(surface, o, sz, style = Stroke(1.dp.toPx()))
            }
            for (s in Spans.intersect(night, listOf(Span(ds, de)))) {
                dashedRect(nightC, Offset(x(s.start), top + 2 * (lane + gap)), Size(maxOf(3.dp.toPx(), x(s.end) - x(s.start)), lane))
            }
        }
    }
    AfLegend(trackerName, color, ecg.isNotEmpty(), night.isNotEmpty())

    // szczegóły wybranego dnia
    val d = rows[sel.coerceIn(0, rows.size - 1)]
    val r = listOf(Span(Dates.startOf(d), Dates.startOf(d.plusDays(1))))
    val items = buildList<AfItem> {
        kreska.filter { Spans.intersect(listOf(it), r).isNotEmpty() }.forEach { add(AfItem(0, it)) }
        ecg.filter { Spans.intersect(listOf(it), r).isNotEmpty() }.forEach { add(AfItem(1, it)) }
        night.filter { Spans.intersect(listOf(it), r).isNotEmpty() }.forEach { add(AfItem(2, it)) }
    }.sortedBy { it.span.start }
    Spacer(Modifier.height(10.dp))
    Text(Dates.longDay(d), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    Column {
        items.forEach { item ->
            val c = when (item.source) { 0 -> color; 1 -> ecgC; else -> nightC }
            val src = when (item.source) { 0 -> trackerName; 1 -> "Polar EKG"; else -> "Rytm Snu" }
            val sd = Dates.zdt(item.span.start).toLocalDate()
            val ed = Dates.zdt(item.span.end).toLocalDate()
            val from = (if (sd != d) Dates.weekday(sd) + " " else "") + Dates.hm(item.span.start)
            val to = (if (ed != d) Dates.weekday(ed) + " " else "") + Dates.hm(item.span.end)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(c, item.source == 2, outlined = item.source == 1)
                Text(
                    "  $src", modifier = Modifier.weight(1f),
                    color = if (item.source == 0) MaterialTheme.colorScheme.onSurface else c,
                    fontWeight = if (item.source == 0) FontWeight.Normal else FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("$from–$to · ${Dates.dur(item.span.ms)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
