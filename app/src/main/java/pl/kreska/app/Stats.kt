package pl.kreska.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class Interval(val start: Long, val end: Long)

/** Statystyki licznika w wybranym okresie. */
class CounterStats(
    /** Dni brane pod uwagę: wybrany okres przycięty do pierwszego wpisu. */
    val days: List<LocalDate>,
    /** Pełne dni (bez dzisiejszego, który jeszcze trwa) — podstawa średnich. */
    val fullDays: Int,
    val since: LocalDate?,
    val trimmed: Boolean,
    val trend: String?,
    val values: List<Float>,
    val sum: Float,
    val avgPerDay: Float,
    val avgActiveDay: Float,
    val median: Float,
    val bestIdx: Int,
    val worstActiveIdx: Int,
    val activeDays: Int,
    val goalDays: Int?,
    val prevSum: Float,
    val streakCurrent: Int,
    val streakBest: Int,
    val goalStreakCurrent: Int?,
    val goalStreakBest: Int?,
    val hours: FloatArray,
    val heat: Array<FloatArray>,
    val weekdayAvg: FloatArray,
    val weekdayTotal: FloatArray,
    val partsOfDay: FloatArray,
    val avgFirstMin: Int?,
    val avgLastMin: Int?,
    val avgGapMs: Long?,
    val longestGapMs: Long?,
    val clicks: Int,
    val weeks: List<Pair<LocalDate, Float>>,
)

/** Statystyki typu zdarzenia w wybranym okresie (czasy bez pauz). */
class TrackerStats(
    val days: List<LocalDate>,
    val fullDays: Int,
    val since: LocalDate?,
    val trimmed: Boolean,
    val trendTotal: String?,
    val trendCount: String?,
    val avgPerDayMs: Long,
    val countPerDay: Float,
    val sessions: List<Session>,
    val pauses: Map<Long, List<Pause>>,
    val activeBySession: Map<Long, Long>,
    val count: Int,
    val totalMs: Long,
    val avgMs: Long,
    val medianMs: Long,
    val shortestMs: Long,
    val longestMs: Long,
    val pausedMs: Long,
    val pauseCount: Int,
    val dailyMinutes: List<Float>,
    val activeDays: Int,
    val prevTotalMs: Long,
    val prevCount: Int,
    val streakCurrent: Int,
    val streakBest: Int,
    val startHours: FloatArray,
    val activeHours: FloatArray,
    val heat: Array<FloatArray>,
    /** Średnia liczba zdarzeń i średni czas (min) w danym dniu tygodnia. */
    val weekdayCount: FloatArray,
    val weekdayMinutes: FloatArray,
    val avgStartMin: Int?,
    val avgEndMin: Int?,
    val buckets: FloatArray,
    val avgGapMs: Long?,
    val longestGapMs: Long?,
    val weeks: List<Pair<LocalDate, Float>>,
)

object Stats {
    val BUCKET_LABELS = listOf("<15 min", "15–30", "30–60", "1–2 h", "2–4 h", ">4 h")
    val PART_LABELS = listOf("rano 5–12", "popołudnie 12–17", "wieczór 17–22", "noc 22–5")

    /* ---------- czas aktywny ---------- */

    fun activeIntervals(s: Session, pauses: List<Pause>, now: Long): List<Interval> {
        val end = s.end ?: now
        if (end <= s.start) return emptyList()
        val ps = pauses
            .map { Interval(maxOf(it.start, s.start), minOf(it.end ?: end, end)) }
            .filter { it.end > it.start }
            .sortedBy { it.start }
        val out = ArrayList<Interval>()
        var cur = s.start
        for (p in ps) {
            if (p.start > cur) out.add(Interval(cur, p.start))
            cur = maxOf(cur, p.end)
        }
        if (end > cur) out.add(Interval(cur, end))
        return out
    }

    fun activeMs(s: Session, pauses: List<Pause>, now: Long): Long =
        activeIntervals(s, pauses, now).sumOf { it.end - it.start }

    /* ---------- pomocnicze ---------- */

    private fun minuteOfDay(ts: Long): Int {
        val z = Dates.zdt(ts)
        return z.hour * 60 + z.minute
    }

    /** Średnia godzina liczona po okręgu, żeby 23:30 i 0:30 dały północ, a nie południe. */
    fun circularMeanMinutes(mins: List<Int>): Int? {
        if (mins.isEmpty()) return null
        var sx = 0.0
        var sy = 0.0
        for (m in mins) {
            val a = m / 1440.0 * 2 * Math.PI
            sx += cos(a); sy += sin(a)
        }
        var a = atan2(sy, sx)
        if (a < 0) a += 2 * Math.PI
        return ((a / (2 * Math.PI)) * 1440).roundToInt() % 1440
    }

    fun medianOf(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun partOfDay(hour: Int): Int = when (hour) {
        in 5..11 -> 0
        in 12..16 -> 1
        in 17..21 -> 2
        else -> 3
    }

    /** Seria: ile kolejnych dni (do dziś lub do wczoraj) spełnia warunek, oraz najdłuższa seria w historii. */
    fun streaks(okDays: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
        if (okDays.isEmpty()) return 0 to 0
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in okDays.sorted()) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        var cur = 0
        var d = if (today in okDays) today else today.minusDays(1)
        while (d in okDays) {
            cur++
            d = d.minusDays(1)
        }
        return cur to best
    }

    private fun weeksOf(days: List<LocalDate>, values: List<Float>): List<Pair<LocalDate, Float>> {
        val map = LinkedHashMap<LocalDate, Float>()
        days.forEachIndexed { i, d ->
            val wk = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            map[wk] = (map[wk] ?: 0f) + values[i]
        }
        return map.entries.map { it.key to it.value }
    }

    private fun weekdayCounts(days: List<LocalDate>): IntArray {
        val n = IntArray(7)
        days.forEach { n[it.dayOfWeek.value - 1]++ }
        return n
    }

    /**
     * Okno liczenia: wybrany okres, ale nie wcześniej niż pierwszy wpis.
     * Dzięki temu średnie nie spadają sztucznie, gdy wybierzesz „rok”, a dane są z tygodnia.
     */
    private fun window(days: List<LocalDate>, since: LocalDate?): List<LocalDate> {
        val today = LocalDate.now()
        val start = since ?: today
        val eff = days.filter { !it.isBefore(start) }
        return if (eff.isEmpty()) listOf(days.last()) else eff
    }

    /** Dni pełne do średnich: bez dzisiejszego (niepełnego), chyba że to jedyny dzień. */
    private fun fullDaysOf(eff: List<LocalDate>): List<LocalDate> {
        val today = LocalDate.now()
        val full = eff.filter { it != today }
        return if (full.isEmpty()) eff else full
    }

    /** Poprzednie okno tej samej długości, też przycięte do pierwszego wpisu. */
    private fun prevWindow(eff: List<LocalDate>, since: LocalDate?): List<LocalDate> {
        if (since == null) return emptyList()
        val n = eff.size.toLong()
        val first = eff.first()
        return (1..n).map { first.minusDays(it) }.filter { !it.isBefore(since) }.reversed()
    }

    /* ---------- licznik ---------- */

    fun counter(c: Counter, requested: List<LocalDate>): CounterStats {
        val since = Repo.firstDay(c.id)
        val days = window(requested, since)
        val from = days.first()
        val to = days.last()
        val totals = Repo.dailyTotals(c.id, from, to)
        val values = days.map { (totals[it.toString()] ?: 0).toFloat() }
        val sum = values.sum()
        val activeDays = values.count { it > 0f }
        val full = fullDaysOf(days).toSet()
        val fullValues = days.indices.filter { days[it] in full }.map { values[it] }
        val fullActive = fullValues.filter { it > 0f }
        val avgPerDay = if (fullValues.isNotEmpty()) fullValues.sum() / fullValues.size else 0f
        var bestIdx = -1
        var worstIdx = -1
        values.forEachIndexed { i, v ->
            if (v > 0f && (bestIdx < 0 || v > values[bestIdx])) bestIdx = i
            if (v > 0f && (worstIdx < 0 || v < values[worstIdx])) worstIdx = i
        }
        val goal = c.goal
        val goalDays = if (goal != null) values.count { it >= goal } else null

        val prevDays = prevWindow(days, since)
        val prevSum = if (prevDays.isEmpty()) 0f
        else Repo.dailyTotals(c.id, prevDays.first(), prevDays.last()).values.sum().toFloat()
        // trend porównuje średnią dzienną, więc różna długość okien nie przekłamuje wyniku
        val trendStr = if (prevDays.isEmpty()) null else trend(avgPerDay.toDouble(), (prevSum / prevDays.size).toDouble())

        // serie z całej historii
        val all = Repo.allDailyTotals(c.id)
        val today = LocalDate.now()
        val activeSet = all.filter { it.value > 0 }.keys.map { LocalDate.parse(it) }.toSet()
        val (sc, sb) = streaks(activeSet, today)
        var gsc: Int? = null
        var gsb: Int? = null
        if (goal != null) {
            val goalSet = all.filter { it.value >= goal }.keys.map { LocalDate.parse(it) }.toSet()
            val g = streaks(goalSet, today)
            gsc = g.first; gsb = g.second
        }

        // rozkład w czasie z pojedynczych kliknięć
        val taps = Repo.taps(c.id, from, to)
        val hours = FloatArray(24)
        val heat = Array(7) { FloatArray(24) }
        val parts = FloatArray(4)
        val firstByDay = HashMap<String, Long>()
        val lastByDay = HashMap<String, Long>()
        val positiveByDay = HashMap<String, MutableList<Long>>()
        var clicks = 0
        for (t in taps) {
            if (t.manual) continue
            val z = Dates.zdt(t.ts)
            hours[z.hour] += t.amount.toFloat()
            heat[z.dayOfWeek.value - 1][z.hour] += t.amount.toFloat()
            parts[partOfDay(z.hour)] += t.amount.toFloat()
            if (t.amount > 0) {
                clicks++
                val k = z.toLocalDate().toString()
                if (!firstByDay.containsKey(k)) firstByDay[k] = t.ts
                lastByDay[k] = t.ts
                positiveByDay.getOrPut(k) { ArrayList() }.add(t.ts)
            }
        }
        for (i in 0 until 24) if (hours[i] < 0f) hours[i] = 0f
        for (r in heat) for (i in 0 until 24) if (r[i] < 0f) r[i] = 0f
        for (i in 0 until 4) if (parts[i] < 0f) parts[i] = 0f
        val partSum = parts.sum()
        if (partSum > 0f) for (i in 0 until 4) parts[i] = parts[i] / partSum * 100f

        val gaps = ArrayList<Long>()
        positiveByDay.values.forEach { list ->
            for (i in 1 until list.size) gaps.add(list[i] - list[i - 1])
        }

        val wdN = weekdayCounts(days.filter { it in full })
        val wdTotal = FloatArray(7)
        days.forEachIndexed { i, d -> if (d in full) wdTotal[d.dayOfWeek.value - 1] += values[i] }
        val wdAvg = FloatArray(7) { if (wdN[it] > 0) wdTotal[it] / wdN[it] else 0f }

        return CounterStats(
            days = days,
            fullDays = full.size,
            since = since,
            trimmed = days.size < requested.size,
            trend = trendStr,
            values = values,
            sum = sum,
            avgPerDay = avgPerDay,
            avgActiveDay = if (fullActive.isNotEmpty()) fullActive.sum() / fullActive.size else 0f,
            median = medianOf(fullActive.map { it.toDouble() }).toFloat(),
            bestIdx = bestIdx,
            worstActiveIdx = worstIdx,
            activeDays = activeDays,
            goalDays = goalDays,
            prevSum = prevSum,
            streakCurrent = sc,
            streakBest = sb,
            goalStreakCurrent = gsc,
            goalStreakBest = gsb,
            hours = hours,
            heat = heat,
            weekdayAvg = wdAvg,
            weekdayTotal = wdTotal,
            partsOfDay = parts,
            avgFirstMin = circularMeanMinutes(firstByDay.values.map { minuteOfDay(it) }),
            avgLastMin = circularMeanMinutes(lastByDay.values.map { minuteOfDay(it) }),
            avgGapMs = if (gaps.isEmpty()) null else gaps.sum() / gaps.size,
            longestGapMs = gaps.maxOrNull(),
            clicks = clicks,
            weeks = weeksOf(days, values),
        )
    }

    /* ---------- zdarzenia ---------- */

    private fun bucketOf(ms: Long): Int {
        val m = ms / 60_000
        return when {
            m < 15 -> 0
            m < 30 -> 1
            m < 60 -> 2
            m < 120 -> 3
            m < 240 -> 4
            else -> 5
        }
    }

    fun tracker(t: Tracker, requested: List<LocalDate>): TrackerStats {
        val since = Repo.firstSessionDay(t.id)
        val days = window(requested, since)
        val from = days.first()
        val n = days.size
        val full = fullDaysOf(days).toSet()
        val now = System.currentTimeMillis()
        val fromTs = Dates.startOf(from)
        val sessions = Repo.sessions(t.id, fromTs)
        val pauses = Repo.pausesFor(sessions.map { it.id })
        val activeBy = HashMap<Long, Long>()
        val dayIndex = HashMap<String, Int>()
        days.forEachIndexed { i, d -> dayIndex[d.toString()] = i }
        val dailyMin = FloatArray(n)
        val activeHours = FloatArray(24)
        val heat = Array(7) { FloatArray(24) }
        val startHours = FloatArray(24)
        val wdCount = FloatArray(7)
        val wdMin = FloatArray(7)
        val buckets = FloatArray(6)
        var pausedMs = 0L
        var pauseCount = 0

        for (s in sessions) {
            val ps = pauses[s.id] ?: emptyList()
            val ivs = activeIntervals(s, ps, now)
            val active = ivs.sumOf { it.end - it.start }
            activeBy[s.id] = active
            pausedMs += (s.end ?: now) - s.start - active
            pauseCount += ps.size
            val zs = Dates.zdt(s.start)
            startHours[zs.hour] += 1f
            if (zs.toLocalDate() in full) wdCount[zs.dayOfWeek.value - 1] += 1f
            buckets[bucketOf(active)] += 1f
            // rozłóż czas aktywny na godziny i dni (zdarzenie przez północ trafia do obu dni)
            for (iv in ivs) {
                var cur = iv.start
                while (cur < iv.end) {
                    val z = Dates.zdt(cur)
                    val hourEnd = z.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                    val e = minOf(hourEnd, iv.end)
                    val m = (e - cur) / 60_000f
                    activeHours[z.hour] += m
                    heat[z.dayOfWeek.value - 1][z.hour] += m
                    if (z.toLocalDate() in full) wdMin[z.dayOfWeek.value - 1] += m
                    val di = dayIndex[z.toLocalDate().toString()]
                    if (di != null) dailyMin[di] += m
                    cur = e
                }
            }
        }

        val actives = sessions.map { activeBy[it.id] ?: 0L }
        val total = actives.sum()
        val count = sessions.size

        // przerwy między kolejnymi zdarzeniami (od końca jednego do startu następnego)
        val chrono = sessions.sortedBy { it.start }
        val gaps = ArrayList<Long>()
        for (i in 1 until chrono.size) {
            val prevEnd = chrono[i - 1].end ?: continue
            val g = chrono[i].start - prevEnd
            if (g > 0) gaps.add(g)
        }

        val prevDays = prevWindow(days, since)
        val prev = if (prevDays.isEmpty()) emptyList()
        else Repo.sessions(t.id, Dates.startOf(prevDays.first())).filter { it.start < fromTs }
        val prevPauses = Repo.pausesFor(prev.map { it.id })
        val prevTotal = prev.sumOf { activeMs(it, prevPauses[it.id] ?: emptyList(), now) }

        // średnie dzienne liczone z pełnych dni (bez dzisiejszego)
        val fullIdx = days.indices.filter { days[it] in full }
        val fullMinutes = fullIdx.sumOf { dailyMin[it].toDouble() }
        val avgPerDayMs = if (fullIdx.isNotEmpty()) (fullMinutes / fullIdx.size * 60_000).toLong() else 0L
        val fullCount = sessions.count { Dates.zdt(it.start).toLocalDate() in full }
        val trendTotal = if (prevDays.isEmpty()) null
        else trend(avgPerDayMs.toDouble(), prevTotal.toDouble() / prevDays.size)
        val trendCount = if (prevDays.isEmpty()) null
        else trend(fullCount.toDouble() / maxOf(1, fullIdx.size), prev.size.toDouble() / prevDays.size)

        val wdN = weekdayCounts(days.filter { it in full })
        for (i in 0 until 7) {
            if (wdN[i] > 0) {
                wdCount[i] /= wdN[i]
                wdMin[i] /= wdN[i]
            } else {
                wdCount[i] = 0f
                wdMin[i] = 0f
            }
        }

        val allDays = Repo.allSessions(t.id).map { Dates.zdt(it.start).toLocalDate() }.toSet()
        val (sc, sb) = streaks(allDays, LocalDate.now())

        val ends = sessions.mapNotNull { it.end }.map { minuteOfDay(it) }

        return TrackerStats(
            days = days,
            fullDays = full.size,
            since = since,
            trimmed = days.size < requested.size,
            trendTotal = trendTotal,
            trendCount = trendCount,
            avgPerDayMs = avgPerDayMs,
            countPerDay = if (fullIdx.isNotEmpty()) fullCount.toFloat() / fullIdx.size else 0f,
            sessions = sessions,
            pauses = pauses,
            activeBySession = activeBy,
            count = count,
            totalMs = total,
            avgMs = if (count > 0) total / count else 0L,
            medianMs = medianOf(actives.map { it.toDouble() }).toLong(),
            shortestMs = actives.minOrNull() ?: 0L,
            longestMs = actives.maxOrNull() ?: 0L,
            pausedMs = pausedMs,
            pauseCount = pauseCount,
            dailyMinutes = dailyMin.toList(),
            activeDays = dailyMin.count { it > 0f },
            prevTotalMs = prevTotal,
            prevCount = prev.size,
            streakCurrent = sc,
            streakBest = sb,
            startHours = startHours,
            activeHours = activeHours,
            heat = heat,
            weekdayCount = wdCount,
            weekdayMinutes = wdMin,
            avgStartMin = circularMeanMinutes(sessions.map { minuteOfDay(it.start) }),
            avgEndMin = circularMeanMinutes(ends),
            buckets = buckets,
            avgGapMs = if (gaps.isEmpty()) null else gaps.sum() / gaps.size,
            longestGapMs = gaps.maxOrNull(),
            weeks = weeksOf(days, dailyMin.toList()),
        )
    }

    /* ---------- formatowanie ---------- */

    fun hhmm(min: Int?): String = if (min == null) "–" else String.format(java.util.Locale.ROOT, "%d:%02d", min / 60, min % 60)

    /** Zmiana względem poprzedniego okresu, np. "+12%" lub "−8%". */
    fun trend(cur: Double, prev: Double): String? {
        if (prev <= 0.0) return if (cur > 0.0) "nowe" else null
        val pct = ((cur - prev) / prev * 100).roundToInt()
        return when {
            pct > 0 -> "+$pct%"
            pct < 0 -> "−${-pct}%"
            else -> "bez zmian"
        }
    }

    /** Opis, z jakich dni liczone są średnie. */
    fun basisNote(fullDays: Int, since: LocalDate?, trimmed: Boolean, trendShown: Boolean): String {
        val today = LocalDate.now()
        val sb = StringBuilder()
        sb.append("Średnie z $fullDays ")
        sb.append(if (fullDays == 1) "dnia" else "dni")
        if (since != null && since != today) sb.append(" (bez dzisiejszego, który jeszcze trwa)")
        sb.append('.')
        if (trimmed && since != null) sb.append(" Dane od ${Dates.date(since)}, wcześniejsze dni okresu pominięte.")
        if (trendShown) sb.append(" W nawiasie zmiana średniej względem poprzedniego okresu tej samej długości.")
        return sb.toString()
    }

    fun minutesLabel(m: Float): String = if (m >= 90f) Num.fmt(m / 60f) + " h" else "${m.roundToInt()} min"
}
