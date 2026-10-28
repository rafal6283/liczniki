package pl.kreska.app

import androidx.compose.foundation.border
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

val WEEKDAYS = listOf("pn", "wt", "śr", "cz", "pt", "sb", "nd")
val HOUR_TIPS = (0 until 24).map { "$it:00–$it:59" }
val WEEKDAYS_LONG = listOf("poniedziałek", "wtorek", "środa", "czwartek", "piątek", "sobota", "niedziela")

/* ---------- wykresy ---------- */

/**
 * Słupki z etykietami pod spodem; opcjonalnie wartości nad słupkami.
 * Słupki wyrastają kaskadą, a dotknięcie / przeciągnięcie palcem pokazuje dymek z wartością.
 */
@Composable
fun LabeledBars(
    labels: List<String>,
    values: List<Float>,
    color: Color,
    format: (Float) -> String,
    labelEvery: Int = 1,
    showValues: Boolean = false,
    chartHeight: Dp = 140.dp,
    tipLabels: List<String>? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    val tipStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.inverseOnSurface)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maxV = values.maxOrNull() ?: 0f
    val peak = if (maxV > 0f) values.indexOf(maxV) else -1
    val anim = animatedSeries(values)
    val yMax by animateFloatAsState(if (maxV > 0f) maxV else 1f, spring(stiffness = Spring.StiffnessLow), label = "yMax")
    val sel = rememberSelection(labels.firstOrNull() to values.size)
    Canvas(
        Modifier.fillMaxWidth().height(chartHeight)
            .chartTouch(sel, values.size) { o, w -> if (values.isEmpty()) -1 else (o.x / (w / values.size)).toInt().coerceIn(0, values.size - 1) }
    ) {
        val top = if (showValues) 16.dp.toPx() else 6.dp.toPx()
        val bottom = 18.dp.toPx()
        val h = size.height - top - bottom
        val n = values.size
        if (n == 0) return@Canvas
        val bw = size.width / n
        val gap = minOf(8.dp.toPx(), bw * 0.25f)
        drawLine(grid, Offset(0f, top + h), Offset(size.width, top + h), strokeWidth = 1f)
        val tipA = sel.alpha.value
        for (i in 0 until n) {
            val v = anim[i]
            val x = i * bw + gap / 2f
            if (v > 0f) {
                val bh = maxOf(1.5f, h * v / yMax).coerceAtMost(h)
                drawRoundRect(
                    color = color.copy(alpha = barAlpha(i, sel, if (i == peak) 1f else 0.72f)),
                    topLeft = Offset(x, top + h - bh),
                    size = Size(maxOf(0.6f, bw - gap), bh),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
                if (showValues && values[i] > 0f) {
                    val t = measurer.measure(format(values[i]), valueStyle)
                    val a = if (i == sel.last) 1f - tipA else 1f
                    drawText(
                        t,
                        topLeft = Offset((i * bw + bw / 2f - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), top + h - bh - t.size.height - 1f),
                        alpha = (anim.t * a).coerceIn(0f, 1f),
                    )
                }
            }
            if (i % labelEvery == 0) {
                val t = measurer.measure(labels[i], labelStyle)
                drawText(t, topLeft = Offset((i * bw + bw / 2f - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
            }
        }
        val s = sel.last
        if (s in 0 until n) {
            val bh = if (anim[s] > 0f) maxOf(1.5f, h * anim[s] / yMax).coerceAtMost(h) else 0f
            val name = tipLabels?.getOrNull(s) ?: labels[s]
            tooltip(measurer, "$name · ${format(values[s])}", s * bw + bw / 2f, top + h - bh, tipStyle, tipBg, tipA, grid, top + h)
        }
    }
}

/** Mapa cieplna: dni tygodnia × godziny. Im ciemniej, tym więcej. Pola rozjaśniają się falą; dotknięcie pola pokazuje wartość. */
@Composable
fun HeatMap(heat: Array<FloatArray>, color: Color, format: (Float) -> String = { Num.fmt(it) }) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.inverseOnSurface)
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val ring = MaterialTheme.colorScheme.onSurface
    val empty = MaterialTheme.colorScheme.surfaceVariant
    var maxV = 0f
    for (r in heat) for (v in r) if (v > maxV) maxV = v
    val key = heat.sumOf { it.contentHashCode() }
    val p = entrance(key, 900)
    val sel = rememberSelection(key)
    val density = LocalDensity.current
    val left = with(density) { 22.dp.toPx() }
    val top = with(density) { 14.dp.toPx() }
    Canvas(
        Modifier.fillMaxWidth().height(170.dp)
            .chartTouch(sel, 7 * 24) { o, w ->
                val cw = (w - left) / 24f
                val ch = with(density) { (170.dp.toPx() - top) / 7f }
                val hr = ((o.x - left) / cw).toInt()
                val d = ((o.y - top) / ch).toInt()
                if (o.x < left || o.y < top || hr !in 0 until 24 || d !in 0 until 7) -1 else d * 24 + hr
            }
    ) {
        val cw = (size.width - left) / 24f
        val ch = (size.height - top) / 7f
        val pad = 1.dp.toPx()
        for (hr in 0 until 24 step 3) {
            val t = measurer.measure("$hr", labelStyle)
            drawText(t, topLeft = Offset(left + hr * cw, 0f))
        }
        val tipA = sel.alpha.value
        for (d in 0 until 7) {
            val t = measurer.measure(WEEKDAYS[d], labelStyle)
            drawText(t, topLeft = Offset(0f, top + d * ch + ch / 2f - t.size.height / 2f))
            for (hr in 0 until 24) {
                val v = heat[d][hr]
                // fala po przekątnej: lewy-górny róg pierwszy
                val q = ((p * 1.6f - (hr / 23f + d / 6f) * 0.3f)).coerceIn(0f, 1f)
                val target = if (v <= 0f || maxV <= 0f) empty else color.copy(alpha = 0.15f + 0.85f * (v / maxV))
                val c = if (target == empty) empty else lerp(empty, target, q)
                val shrink = (1f - q) * cw * 0.25f
                drawRoundRect(
                    color = c,
                    topLeft = Offset(left + hr * cw + pad + shrink, top + d * ch + pad + shrink),
                    size = Size(maxOf(0f, cw - 2 * pad - 2 * shrink), maxOf(0f, ch - 2 * pad - 2 * shrink)),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
        }
        val s = sel.last
        if (s >= 0 && tipA > 0f) {
            val d = s / 24
            val hr = s % 24
            drawRoundRect(
                ring.copy(alpha = tipA),
                Offset(left + hr * cw, top + d * ch),
                Size(cw, ch),
                CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = Stroke(1.5.dp.toPx()),
            )
            tooltip(measurer, "${WEEKDAYS[d]} $hr:00 · ${format(heat[d][hr])}", left + hr * cw + cw / 2f, top + d * ch, tipStyle, tipBg, tipA)
        }
    }
}

/* ---------- klocki ---------- */

@Composable
fun StatSection(title: String, hint: String? = null, imported: String? = null, content: @Composable () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (imported != null) ImportedTag(imported)
        }
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
    onDay: (LocalDate) -> Unit,
) {
    val st = remember(v, days, c.id) { Stats.counter(c, days) }
    val col = Palette.color(c.color)
    val dd = st.days
    val n = dd.size
    val f: (Float) -> String = { Num.fmt(it) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DetailHeader("Licznik", c.name, col, range, onRange, onBack) }

        item {
            StatSection("Podsumowanie", Stats.basisNote(st.fullDays, st.since, st.trimmed, st.trend != null)) {
                val tr = st.trend
                StatsGrid(
                    listOf(
                        Num.fmt(st.sum) to "suma w okresie",
                        Num.fmt(st.avgPerDay) to ("średnio / dzień" + if (tr != null) " ($tr)" else ""),
                        Num.fmt(st.avgActiveDay) to "średnio w dniu z wpisem",
                        Num.fmt(st.median) to "mediana dnia z wpisem",
                        (if (st.bestIdx >= 0) Num.fmt(st.values[st.bestIdx]) else "–") to
                            (if (st.bestIdx >= 0) "rekord, ${Dates.day(dd[st.bestIdx])}" else "rekord"),
                        (if (st.worstActiveIdx >= 0) Num.fmt(st.values[st.worstActiveIdx]) else "–") to
                            (if (st.worstActiveIdx >= 0) "najmniej, ${Dates.day(dd[st.worstActiveIdx])}" else "najmniej"),
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
                BarChart(dd, st.values, col, f)
                if (n >= 28 && st.weeks.size >= 2) {
                    Spacer(Modifier.height(14.dp))
                    Eyebrow("Tydzień po tygodniu (od poniedziałku)")
                    Spacer(Modifier.height(6.dp))
                    LabeledBars(weekLabels(st.weeks), st.weeks.map { it.second }, col, f, labelEvery = maxOf(1, st.weeks.size / 6), tipLabels = weekLabels(st.weeks).map { "tydz. od $it" })
                }
            }
        }

        item {
            StatSection("Pora dnia", peakText(st.hours, f) ?: "Brak kliknięć w tym okresie.") {
                LabeledBars((0 until 24).map { "$it" }, st.hours.toList(), col, f, labelEvery = 3, tipLabels = HOUR_TIPS)
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
            StatSection("Dni tygodnia", if (best >= 0) "Średni wynik w danym dniu tygodnia. Najwięcej: ${WEEKDAYS_LONG[best]}." else "Średni wynik w danym dniu tygodnia.") {
                LabeledBars(WEEKDAYS, st.weekdayAvg.toList(), col, f, showValues = true, tipLabels = WEEKDAYS_LONG)
            }
        }

        item {
            StatSection("Mapa tygodnia", "Dzień tygodnia × godzina. Ciemniejsze pole = więcej kliknięć.") {
                HeatMap(st.heat, col)
            }
        }

        item {
            StatSection("Historia dni", "Dotknij dnia, aby zobaczyć, dodać lub poprawić jego wpisy.") {
                Row {
                    TextButton(onClick = { onDay(LocalDate.now()) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Dodaj wpis") }
                    Spacer(Modifier.width(16.dp))
                    TextButton(onClick = onSetDay, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Ustaw wartość dnia") }
                }
                val rows = dd.indices.filter { st.values[it] != 0f }.reversed()
                if (rows.isEmpty()) {
                    Text("Brak wpisów w tym okresie.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                rows.take(120).forEach { i ->
                    Row(Modifier.fillMaxWidth().clickable { onDay(dd[i]) }.padding(vertical = 8.dp)) {
                        Text(Dates.day(dd[i]), modifier = Modifier.weight(1f))
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
    val dd = st.days
    val n = dd.size
    val now = System.currentTimeMillis()
    val fm: (Float) -> String = { Stats.minutesLabel(it) }
    val fc: (Float) -> String = { Num.fmt(it) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DetailHeader("Zdarzenie", t.name, col, range, onRange, onBack) }

        item {
            StatSection("Podsumowanie", "Czasy bez pauz. " + Stats.basisNote(st.fullDays, st.since, st.trimmed, st.trendTotal != null)) {
                val trC = st.trendCount
                val trT = st.trendTotal
                StatsGrid(
                    listOf(
                        "${st.count}" to "razy w okresie",
                        (if (st.totalMs > 0) Dates.dur(st.totalMs) else "–") to "łącznie w okresie",
                        Num.fmt(st.countPerDay) to ("razy dziennie" + if (trC != null) " ($trC)" else ""),
                        Dates.dur(st.avgPerDayMs) to ("czasu dziennie" + if (trT != null) " ($trT)" else ""),
                        (if (st.count > 0) Dates.dur(st.avgMs) else "–") to "średnio jedno",
                        (if (st.count > 0) Dates.dur(st.medianMs) else "–") to "mediana",
                        (if (st.count > 0) Dates.dur(st.shortestMs) else "–") to "najkrócej",
                        (if (st.count > 0) Dates.dur(st.longestMs) else "–") to "najdłużej",
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
                BarChart(dd, st.dailyMinutes, col, fm)
                if (n >= 28 && st.weeks.size >= 2) {
                    Spacer(Modifier.height(14.dp))
                    Eyebrow("Tydzień po tygodniu (od poniedziałku)")
                    Spacer(Modifier.height(6.dp))
                    LabeledBars(weekLabels(st.weeks), st.weeks.map { it.second }, col, fm, labelEvery = maxOf(1, st.weeks.size / 6), tipLabels = weekLabels(st.weeks).map { "tydz. od $it" })
                }
            }
        }

        item {
            StatSection("Kiedy się zaczyna", "Średnio start o ${Stats.hhmm(st.avgStartMin)}, koniec o ${Stats.hhmm(st.avgEndMin)}.") {
                LabeledBars((0 until 24).map { "$it" }, st.startHours.toList(), col, fc, labelEvery = 3, tipLabels = HOUR_TIPS)
            }
        }

        item {
            val parts = FloatArray(4)
            st.activeHours.forEachIndexed { h, m -> parts[Stats.partOfDay(h)] += m }
            val ps = parts.sum()
            StatSection("Kiedy trwa", peakText(st.activeHours, fm) ?: "Brak zdarzeń w tym okresie.") {
                LabeledBars((0 until 24).map { "$it" }, st.activeHours.toList(), col, fm, labelEvery = 3, tipLabels = HOUR_TIPS)
                Spacer(Modifier.height(10.dp))
                Stats.PART_LABELS.forEachIndexed { i, label ->
                    InfoLine(label, if (ps > 0f) "${(parts[i] / ps * 100).toInt()}% · ${fm(parts[i])}" else "–")
                }
            }
        }

        item {
            StatSection("Dni tygodnia", "Średni czas i średnia liczba zdarzeń w danym dniu tygodnia.") {
                LabeledBars(WEEKDAYS, st.weekdayMinutes.toList(), col, fm, showValues = true, tipLabels = WEEKDAYS_LONG)
                Spacer(Modifier.height(8.dp))
                Eyebrow("Średnia liczba zdarzeń")
                LabeledBars(WEEKDAYS, st.weekdayCount.toList(), col, fc, showValues = true, chartHeight = 100.dp, tipLabels = WEEKDAYS_LONG)
            }
        }

        item {
            StatSection("Mapa tygodnia", "Dzień tygodnia × godzina. Ciemniejsze pole = więcej czasu.") {
                HeatMap(st.heat, col, fm)
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


/**
 * Delikatne wyróżnienie danych z współpracujących aplikacji (Rytm Snu, Polar EKG):
 * mała etykieta w obwódce, np. „z Rytmu Snu”.
 */
@Composable
fun ImportedTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

fun importedFrom(source: String) = when (source) {
    SRC_NIGHT, SRC_NIGHT_NEW -> "z Rytmu Snu"
    SRC_ECG -> "z Polar EKG"
    else -> "zaimportowane"
}
