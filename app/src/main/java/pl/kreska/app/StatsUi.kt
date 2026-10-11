package pl.kreska.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

val WEEKDAYS = listOf("pn", "wt", "śr", "cz", "pt", "sb", "nd")
val WEEKDAYS_LONG = listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")

/* ---------- wykresy ---------- */

/** Słupki z etykietami pod spodem; opcjonalnie wartości nad słupkami. */
@Composable
fun LabeledBars(
    labels: List<String>,
    values: List<Float>,
    color: Color,
    format: (Float) -> String,
    labelEvery: Int = 1,
    showValues: Boolean = false,
    chartHeight: Dp = 140.dp,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maxV = values.maxOrNull() ?: 0f
    val peak = if (maxV > 0f) values.indexOf(maxV) else -1
    Canvas(Modifier.fillMaxWidth().height(chartHeight)) {
        val top = if (showValues) 16.dp.toPx() else 6.dp.toPx()
        val bottom = 18.dp.toPx()
        val h = size.height - top - bottom
        val n = values.size
        if (n == 0) return@Canvas
        val bw = size.width / n
        val gap = minOf(8.dp.toPx(), bw * 0.25f)
        val yMax = if (maxV > 0f) maxV else 1f
        drawLine(grid, Offset(0f, top + h), Offset(size.width, top + h), strokeWidth = 1f)
        values.forEachIndexed { i, v ->
            val x = i * bw + gap / 2f
            if (v > 0f) {
                val bh = maxOf(1.5f, h * v / yMax)
                drawRoundRect(
                    color = if (i == peak) color else color.copy(alpha = 0.72f),
                    topLeft = Offset(x, top + h - bh),
                    size = Size(maxOf(0.6f, bw - gap), bh),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
                if (showValues) {
                    val t = measurer.measure(format(v), valueStyle)
                    drawText(t, topLeft = Offset((i * bw + bw / 2f - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), top + h - bh - t.size.height - 1f))
                }
            }
            if (i % labelEvery == 0) {
                val t = measurer.measure(labels[i], labelStyle)
                drawText(t, topLeft = Offset((i * bw + bw / 2f - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
            }
        }
    }
}

/** Mapa cieplna: dni tygodnia × godziny. Im ciemniej, tym więcej. */
@Composable
fun HeatMap(heat: Array<FloatArray>, color: Color) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    var maxV = 0f
    for (r in heat) for (v in r) if (v > maxV) maxV = v
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val left = 22.dp.toPx()
        val top = 14.dp.toPx()
        val cw = (size.width - left) / 24f
        val ch = (size.height - top) / 7f
        val pad = 1.dp.toPx()
        for (hr in 0 until 24 step 3) {
            val t = measurer.measure("$hr", labelStyle)
            drawText(t, topLeft = Offset(left + hr * cw, 0f))
        }
        for (d in 0 until 7) {
            val t = measurer.measure(WEEKDAYS[d], labelStyle)
            drawText(t, topLeft = Offset(0f, top + d * ch + ch / 2f - t.size.height / 2f))
            for (hr in 0 until 24) {
                val v = heat[d][hr]
                val c = if (v <= 0f || maxV <= 0f) empty else color.copy(alpha = 0.15f + 0.85f * (v / maxV))
                drawRoundRect(
                    color = c,
                    topLeft = Offset(left + hr * cw + pad, top + d * ch + pad),
                    size = Size(cw - 2 * pad, ch - 2 * pad),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
    }
}

/* ---------- klocki ---------- */

@Composable
fun StatSection(title: String, hint: String? = null, content: @Composable () -> Unit) {
    AppCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeChips(range: Int, onRange: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(7, 30, 90, 365).forEach { r ->
            FilterChip(selected = range == r, onClick = { onRange(r) }, label = { Text(if (r == 365) "rok" else "$r dni") })
        }
    }
}

@Composable
fun DetailHeader(eyebrow: String, name: String, color: Color, range: Int, onRange: (Int) -> Unit, onBack: () -> Unit) {
    Column {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("← Raporty") }
        Eyebrow(eyebrow)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(color, 12)
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        RangeChips(range, onRange)
    }
}

private fun weekLabels(weeks: List<Pair<LocalDate, Float>>): List<String> =
    weeks.map { "${it.first.dayOfMonth}.${it.first.monthValue.toString().padStart(2, '0')}" }

private fun peakText(values: FloatArray, format: (Float) -> String): String? {
    val total = values.sum()
    if (total <= 0f) return null
    var best = 0
    for (i in values.indices) if (values[i] > values[best]) best = i
    val pct = (values[best] / total * 100).toInt()
    return "Najwięcej o $best:00–$best:59 · ${format(values[best])} ($pct%)"
}

/* ---------- licznik ---------- */

@Composable
fun CounterDetail(
    c: Counter,
    days: List<LocalDate>,
    range: Int,
    onRange: (Int) -> Unit,
    v: Long,
    onBack: () -> Unit,
    onSetDay: () -> Unit,
) {
    val st = remember(v, days, c.id) { Stats.counter(c, days) }
    val col = Palette.color(c.color)
    val n = days.size
    val f: (Float) -> String = { Num.fmt(it) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DetailHeader("Licznik", c.name, col, range, onRange, onBack) }

        item {
            StatSection("Podsumowanie", "Porównanie z poprzednimi $n dniami w nawiasie.") {
                val tr = Stats.trend(st.sum.toDouble(), st.prevSum.toDouble())
                StatsGrid(
                    listOf(
                        Num.fmt(st.sum) to ("suma" + if (tr != null) " ($tr)" else ""),
                        Num.fmt(st.avgPerDay) to "średnio / dzień",
                        Num.fmt(st.avgActiveDay) to "średnio w dniu z wpisem",
                        Num.fmt(st.median) to "mediana dnia z wpisem",
                        (if (st.bestIdx >= 0) Num.fmt(st.values[st.bestIdx]) else "–") to
                            (if (st.bestIdx >= 0) "rekord, ${Dates.day(days[st.bestIdx])}" else "rekord"),
                        (if (st.worstActiveIdx >= 0) Num.fmt(st.values[st.worstActiveIdx]) else "–") to
                            (if (st.worstActiveIdx >= 0) "najmniej, ${Dates.day(days[st.worstActiveIdx])}" else "najmniej"),
                        "${st.activeDays}/$n" to "dni z wpisem",
                        Num.fmt(st.clicks) to "kliknięć +",
                    ) + (if (st.goalDays != null) listOf("${st.goalDays}/$n" to "dni z osiągniętym celem") else emptyList())
                )
            }
        }

        item {
            StatSection("Serie", "Liczone z całej historii.") {
                InfoLine("Obecna seria dni z wpisem", "${st.streakCurrent} d")
                InfoLine("Najdłuższa seria", "${st.streakBest} d")
                if (st.goalStreakCurrent != null) {
                    InfoLine("Obecna seria z celem", "${st.goalStreakCurrent} d")
                    InfoLine("Najdłuższa seria z celem", "${st.goalStreakBest ?: 0} d")
                }
            }
        }

        item {
            StatSection("Dzień po dniu") {
                BarChart(days, st.values, col, f)
                if (n >= 28 && st.weeks.size >= 2) {
                    Spacer(Modifier.height(14.dp))
                    Eyebrow("Tydzień po tygodniu (od poniedziałku)")
                    Spacer(Modifier.height(6.dp))
                    LabeledBars(weekLabels(st.weeks), st.weeks.map { it.second }, col, f, labelEvery = maxOf(1, st.weeks.size / 6))
                }
            }
        }

        item {
            StatSection("Pora dnia", peakText(st.hours, f) ?: "Brak kliknięć w tym okresie.") {
                LabeledBars((0 until 24).map { "$it" }, st.hours.toList(), col, f, labelEvery = 3)
                Spacer(Modifier.height(10.dp))
                Stats.PART_LABELS.forEachIndexed { i, label -> InfoLine(label, "${st.partsOfDay[i].toInt()}%") }
            }
        }

        item {
            StatSection("Rytm dnia", "Średnie liczone z dni, w których były kliknięcia.") {
                InfoLine("Pierwsze kliknięcie dnia", Stats.hhmm(st.avgFirstMin))
                InfoLine("Ostatnie kliknięcie dnia", Stats.hhmm(st.avgLastMin))
                InfoLine("Średni odstęp między kliknięciami", st.avgGapMs?.let { Dates.dur(it) } ?: "–")
                InfoLine("Najdłuższa przerwa w ciągu dnia", st.longestGapMs?.let { Dates.dur(it) } ?: "–")
                InfoLine("Kliknięć na dzień z wpisem", if (st.activeDays > 0) Num.fmt(st.clicks.toFloat() / st.activeDays) else "–")
            }
        }

        item {
            var best = -1
            for (i in 0 until 7) if (st.weekdayAvg[i] > 0f && (best < 0 || st.weekdayAvg[i] > st.weekdayAvg[best])) best = i
            StatSection("Dni tygodnia", if (best >= 0) "Średnio najwięcej: ${WEEKDAYS_LONG[best]}" else "Średni wynik dnia") {
                LabeledBars(WEEKDAYS, st.weekdayAvg.toList(), col, f, showValues = true)
            }
        }

        item {
            StatSection("Mapa tygodnia", "Dzień tygodnia × godzina. Ciemniejsze pole = więcej kliknięć.") {
                HeatMap(st.heat, col)
            }
        }

        item {
            StatSection("Historia dni") {
                TextButton(onClick = onSetDay, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Ustaw wartość wybranego dnia") }
                val rows = days.indices.filter { st.values[it] != 0f }.reversed()
                if (rows.isEmpty()) {
                    Text("Brak wpisów w tym okresie.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                rows.take(120).forEach { i ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(Dates.day(days[i]), modifier = Modifier.weight(1f))
                        val g = c.goal
                        if (g != null && st.values[i] >= g) {
                            Text("cel ✓  ", color = col, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(Num.fmt(st.values[i]), fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/* ---------- zdarzenie ---------- */

@Composable
fun TrackerDetail(
    t: Tracker,
    days: List<LocalDate>,
    range: Int,
    onRange: (Int) -> Unit,
    v: Long,
    onBack: () -> Unit,
    onEdit: (Session) -> Unit,
    onAdd: () -> Unit,
) {
    val st = remember(v, days, t.id) { Stats.tracker(t, days) }
    val col = Palette.color(t.color)
    val n = days.size
    val now = System.currentTimeMillis()
    val fm: (Float) -> String = { Stats.minutesLabel(it) }
    val fc: (Float) -> String = { Num.fmt(it) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DetailHeader("Zdarzenie", t.name, col, range, onRange, onBack) }

        item {
            StatSection("Podsumowanie", "Czasy bez pauz. Porównanie z poprzednimi $n dniami w nawiasie.") {
                val trC = Stats.trend(st.count.toDouble(), st.prevCount.toDouble())
                val trT = Stats.trend(st.totalMs.toDouble(), st.prevTotalMs.toDouble())
                StatsGrid(
                    listOf(
                        "${st.count}" to ("razy" + if (trC != null) " ($trC)" else ""),
                        (if (st.totalMs > 0) Dates.dur(st.totalMs) else "–") to ("łącznie" + if (trT != null) " ($trT)" else ""),
                        (if (st.count > 0) Dates.dur(st.avgMs) else "–") to "średnio",
                        (if (st.count > 0) Dates.dur(st.medianMs) else "–") to "mediana",
                        (if (st.count > 0) Dates.dur(st.shortestMs) else "–") to "najkrócej",
                        (if (st.count > 0) Dates.dur(st.longestMs) else "–") to "najdłużej",
                        Dates.dur(st.totalMs / n) to "średnio dziennie",
                        "${st.activeDays}/$n" to "dni ze zdarzeniem",
                        "${st.pauseCount}" to "pauz",
                        (if (st.pausedMs > 0) Dates.dur(st.pausedMs) else "–") to "łącznie na pauzie",
                    )
                )
            }
        }

        item {
            StatSection("Serie i przerwy") {
                InfoLine("Obecna seria dni ze zdarzeniem", "${st.streakCurrent} d")
                InfoLine("Najdłuższa seria", "${st.streakBest} d")
                InfoLine("Średnia przerwa między zdarzeniami", st.avgGapMs?.let { Dates.dur(it) } ?: "–")
                InfoLine("Najdłuższa przerwa", st.longestGapMs?.let { Dates.dur(it) } ?: "–")
            }
        }

        item {
            StatSection("Czas dzień po dniu") {
                BarChart(days, st.dailyMinutes, col, fm)
                if (n >= 28 && st.weeks.size >= 2) {
                    Spacer(Modifier.height(14.dp))
                    Eyebrow("Tydzień po tygodniu (od poniedziałku)")
                    Spacer(Modifier.height(6.dp))
                    LabeledBars(weekLabels(st.weeks), st.weeks.map { it.second }, col, fm, labelEvery = maxOf(1, st.weeks.size / 6))
                }
            }
        }

        item {
            StatSection("Kiedy się zaczyna", "Średnio start o ${Stats.hhmm(st.avgStartMin)}, koniec o ${Stats.hhmm(st.avgEndMin)}.") {
                LabeledBars((0 until 24).map { "$it" }, st.startHours.toList(), col, fc, labelEvery = 3)
            }
        }

        item {
            val parts = FloatArray(4)
            st.activeHours.forEachIndexed { h, m -> parts[Stats.partOfDay(h)] += m }
            val ps = parts.sum()
            StatSection("Kiedy trwa", peakText(st.activeHours, fm) ?: "Brak zdarzeń w tym okresie.") {
                LabeledBars((0 until 24).map { "$it" }, st.activeHours.toList(), col, fm, labelEvery = 3)
                Spacer(Modifier.height(10.dp))
                Stats.PART_LABELS.forEachIndexed { i, label ->
                    InfoLine(label, if (ps > 0f) "${(parts[i] / ps * 100).toInt()}% · ${fm(parts[i])}" else "–")
                }
            }
        }

        item {
            StatSection("Dni tygodnia", "Łączny czas oraz liczba zdarzeń w każdym dniu tygodnia.") {
                LabeledBars(WEEKDAYS, st.weekdayMinutes.toList(), col, fm, showValues = true)
                Spacer(Modifier.height(8.dp))
                Eyebrow("Liczba zdarzeń")
                LabeledBars(WEEKDAYS, st.weekdayCount.toList(), col, fc, showValues = true, chartHeight = 100.dp)
            }
        }

        item {
            StatSection("Mapa tygodnia", "Dzień tygodnia × godzina. Ciemniejsze pole = więcej czasu.") {
                HeatMap(st.heat, col)
            }
        }

        item {
            StatSection("Długość zdarzeń", "Ile zdarzeń trwało tyle czasu.") {
                LabeledBars(Stats.BUCKET_LABELS, st.buckets.toList(), col, fc, showValues = true, chartHeight = 120.dp)
            }
        }

        item {
            StatSection("Wpisy (${st.count})") {
                TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Dopisz zdarzenie") }
                if (st.sessions.isEmpty()) {
                    Text("Brak wpisów w tym okresie.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    Column {
                        st.sessions.take(150).forEach { s ->
                            SessionRow(s, t, now, st.activeBySession[s.id], st.pauses[s.id]?.size ?: 0) { onEdit(s) }
                        }
                    }
                }
            }
        }
    }
}
