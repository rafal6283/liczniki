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
 * FF z Rytmu Snu na wykresach licznika FF – tak samo jak migotanie na wykresach AFIB:
 * wiatry wykryte w nocy (bez odrzuconych ✗) w kolorze Rytmu Snu, w paski; Twoje kliknięcia w kolorze licznika.
 */
object FfNight {
    class Data(val detected: List<Long>, val windows: List<Span>) {
        val any get() = detected.isNotEmpty() || windows.isNotEmpty()
    }

    fun load(): Data = Data(
        Repo.extEvents(SRC_NIGHT, "fart").filter { it.verified != false }.map { it.start }.sorted(),
        Spans.union(Repo.extWindows(SRC_NIGHT)),
    )

    /** Dane dla licznika porównywanego z Rytmem Snu (ustawienia porównania), inaczej null. */
    fun forCounter(ctx: android.content.Context, counterId: Long): Data? =
        if (ComparePrefs.counterId(ctx, Repo.counters()) == counterId) load().takeIf { it.any } else null

    fun daily(times: List<Long>, days: List<LocalDate>): List<Float> = days.map { d ->
        val a = Dates.startOf(d)
        val b = Dates.startOf(d.plusDays(1))
        times.count { it in a until b }.toFloat()
    }

    /** Kliknięcia z godziną (minus cofa ostatnie kliknięcie z tego dnia; korekty dnia bez godziny pominięte). */
    fun taps(counterId: Long, from: LocalDate, to: LocalDate): List<Long> {
        val byDay = LinkedHashMap<String, ArrayList<Long>>()
        for (t in Repo.taps(counterId, from, to)) {
            if (t.manual) continue
            val l = byDay.getOrPut(Dates.keyOf(t.ts)) { ArrayList() }
            if (t.amount > 0) repeat(t.amount) { l.add(t.ts) } else repeat(-t.amount) { if (l.isNotEmpty()) l.removeAt(l.size - 1) }
        }
        return byDay.values.flatten().sorted()
    }
}

@Composable
fun FfLegend(counterName: String, counterColor: Color, night: Boolean) {
    val n = AfColors.night()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Box(Modifier.size(12.dp, 10.dp).background(counterColor, RoundedCornerShape(2.dp)))
        Text(" $counterName (Kreska)", style = MaterialTheme.typography.labelSmall)
        if (night) {
            Spacer(Modifier.width(10.dp))
            Canvas(Modifier.size(12.dp, 10.dp)) { dashedRect(n, Offset.Zero, size) }
            Text(" Rytm Snu (wykryte w nocy)", style = MaterialTheme.typography.labelSmall, color = n, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Oś czasu FF: każdy dzień to wiersz 0–24 h. Tło – noce nagrywane przez Rytm Snu, górny pas – Twoje kliknięcia,
 * dolny – wiatry wykryte przez Rytm Snu. Dotknięcie dnia pokazuje godziny i ile było wspólnych (±tolerancja).
 */
@Composable
fun FfTimeline(days: List<LocalDate>, taps: List<Long>, detected: List<Long>, windows: List<Span>, counterName: String, color: Color, tolSec: Int) {
    val nightC = AfColors.night()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val selBg = MaterialTheme.colorScheme.surfaceVariant
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val haptic = LocalHapticFeedback.current

    fun inDay(l: List<Long>, d: LocalDate): List<Long> {
        val a = Dates.startOf(d)
        val b = Dates.startOf(d.plusDays(1))
        return l.filter { it in a until b }
    }

    val rows = remember(days, taps, detected) {
        days.reversed().filter { inDay(taps, it).isNotEmpty() || inDay(detected, it).isNotEmpty() }.take(21)
    }
    if (rows.isEmpty()) {
        Text("Brak FF w Kresce ani wykryć z Rytmu Snu w tym okresie.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val firstExt = rows.indexOfFirst { inDay(detected, it).isNotEmpty() }
    var sel by remember(rows) { mutableIntStateOf(maxOf(0, firstExt)) }
    val rowH = 34.dp
    val head = 16.dp
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
        val left = 64.dp.toPx()
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
            val lane = 10.dp.toPx()
            val gap = 2.dp.toPx()
            val top = y0 + (rowPx - 2 * lane - gap) / 2f
            drawRoundRect(grid.copy(alpha = 0.35f), Offset(left, top), Size(w, 2 * lane + gap), CornerRadius(3.dp.toPx()))
            // noce nagrywane przez Rytm Snu – delikatne tło pod dolnym pasem
            for (s in Spans.intersect(windows, listOf(Span(ds, de)))) {
                drawRect(nightC.copy(alpha = 0.18f), Offset(x(s.start), top + lane + gap), Size(maxOf(1f, x(s.end) - x(s.start)), lane))
            }
            val tick = maxOf(1.5.dp.toPx(), 2.dp.toPx())
            for (t in inDay(taps, d)) {
                drawRect(color, Offset(x(t) - tick / 2f, top), Size(tick, lane))
            }
            for (t in inDay(detected, d)) {
                val cx = x(t)
                drawRect(nightC, Offset(cx - tick / 2f, top + lane + gap), Size(tick, lane))
                drawCircle(nightC, 2.5.dp.toPx(), Offset(cx, top + lane + gap + lane / 2f), style = Stroke(1.dp.toPx()))
            }
        }
    }
    FfLegend(counterName, color, detected.isNotEmpty())

    // szczegóły wybranego dnia
    val d = rows[sel.coerceIn(0, rows.size - 1)]
    val mine = inDay(taps, d)
    val det = inDay(detected, d)
    val wins = windows.filter { it.end > Dates.startOf(d) && it.start < Dates.startOf(d.plusDays(1)) }
    val inNight = mine.filter { t -> wins.any { t >= it.start && t < it.end } }
    val matched = FartMerge.matchCount(inNight, det, tolSec * 1000L)
    Spacer(Modifier.height(10.dp))
    Text(Dates.longDay(d), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Box(Modifier.padding(top = 4.dp).size(12.dp, 10.dp).background(color, RoundedCornerShape(2.dp)))
            Text(
                "  $counterName (${mine.size}): " + (if (mine.isEmpty()) "–" else mine.joinToString(", ") { Dates.hm(it) }),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Canvas(Modifier.padding(top = 4.dp).size(12.dp, 10.dp)) { dashedRect(nightC, Offset.Zero, size) }
            Text(
                "  Rytm Snu (${det.size}): " + (if (det.isEmpty()) "–" else det.joinToString(", ") { Dates.hm(it) }),
                style = MaterialTheme.typography.bodyMedium, color = nightC, fontWeight = FontWeight.Bold,
            )
        }
        if (wins.isNotEmpty()) {
            Text(
                "Noc: " + wins.joinToString(", ") { "${Dates.hm(it.start)}–${Dates.hm(it.end)}" } +
                    " · w nocy Ty ${inNight.size}, detektor ${det.size}, wspólne $matched (±$tolSec s), " +
                    "łącznie bez podwójnego liczenia ${mine.size + det.size - matched}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
