package pl.kreska.app

import kotlin.math.abs

/*
 * Porównanie wpisów z Kreski z danymi z innych aplikacji (czysta logika, bez Androida).
 *
 *  - FF ↔ wiatry z Rytmu Snu: zdarzenia punktowe, dopasowanie 1:1 w oknie ±tolerancja.
 *  - AFIB ↔ migotanie z Polar EKG: odcinki czasu, część wspólna / tylko EKG / tylko Kreska.
 *
 * Porównujemy tylko tam, gdzie druga aplikacja faktycznie mierzyła (okna pomiaru: noce, zapisy EKG).
 * Poza oknami liczy się wyłącznie Kreska.
 */

/** Przedział czasu [start, end) w ms. */
data class Span(val start: Long, val end: Long) {
    val ms: Long get() = end - start
}

object Spans {
    /** Scala nakładające się i stykające przedziały, sortuje. */
    fun union(list: List<Span>): List<Span> {
        val sorted = list.filter { it.end > it.start }.sortedBy { it.start }
        val out = ArrayList<Span>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && s.start <= last.end) {
                out[out.size - 1] = Span(last.start, maxOf(last.end, s.end))
            } else out.add(s)
        }
        return out
    }

    /** Część wspólna dwóch zbiorów przedziałów (oba po union). */
    fun intersect(a: List<Span>, b: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val s = maxOf(a[i].start, b[j].start)
            val e = minOf(a[i].end, b[j].end)
            if (e > s) out.add(Span(s, e))
            if (a[i].end < b[j].end) i++ else j++
        }
        return out
    }

    /** a minus b (oba po union). */
    fun subtract(a: List<Span>, b: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        var j = 0
        for (s in a) {
            var cur = s.start
            while (j < b.size && b[j].end <= cur) j++
            var k = j
            while (k < b.size && b[k].start < s.end) {
                if (b[k].start > cur) out.add(Span(cur, b[k].start))
                cur = maxOf(cur, b[k].end)
                k++
            }
            if (cur < s.end) out.add(Span(cur, s.end))
        }
        return out
    }

    fun total(list: List<Span>): Long = list.sumOf { it.ms }

    fun clip(list: List<Span>, range: Span): List<Span> = intersect(union(list), listOf(range))

    fun contains(list: List<Span>, t: Long): Boolean = list.any { t >= it.start && t < it.end }
}

/* ---------- FF ↔ wiatry ---------- */

/** Wynik dla jednego okna pomiaru (nocy). */
data class FartWindow(
    val window: Span,
    val manual: Int, // wpisy FF z Kreski w tym oknie
    val detected: Int, // wykrycia (bez odrzuconych ✗)
    val matched: Int, // pary wpis ↔ wykrycie
) {
    val onlyDetector: Int get() = detected - matched
    val onlyManual: Int get() = manual - matched

    /** Najlepsze oszacowanie: suma bez podwójnego liczenia. */
    val combined: Int get() = matched + onlyDetector + onlyManual
}

data class FartComparison(
    val windows: List<FartWindow>,
    val manualOutside: Int, // wpisy FF poza nocami (dzień) – liczy tylko Kreska
) {
    val manual: Int get() = windows.sumOf { it.manual }
    val detected: Int get() = windows.sumOf { it.detected }
    val matched: Int get() = windows.sumOf { it.matched }
    val combined: Int get() = windows.sumOf { it.combined } + manualOutside

    /** Ile wykryć potwierdziłeś wpisem (gdy byłeś przytomny i klikałeś, to orientacyjna precyzja). */
    val confirmedShare: Double? get() = if (detected > 0) matched.toDouble() / detected else null

    /** Ile Twoich nocnych wpisów detektor też złapał (czułość detektora). */
    val caughtShare: Double? get() = if (manual > 0) matched.toDouble() / manual else null
}

object FartMerge {
    /**
     * @param manual czasy kliknięć FF (kliknięcie +n daje n pozycji)
     * @param detected czasy wykryć (odrzucone już odfiltrowane)
     * @param windows noce z Rytmu Snu
     */
    fun compare(manual: List<Long>, detected: List<Long>, windows: List<Span>, range: Span, tolMs: Long): FartComparison {
        val ws = Spans.clip(windows, range)
        val man = manual.filter { it >= range.start && it < range.end }.sorted()
        val det = detected.filter { it >= range.start && it < range.end }.sorted()
        val res = ArrayList<FartWindow>()
        var inside = 0
        for (w in ws) {
            val m = man.filter { it >= w.start && it < w.end }
            val d = det.filter { it >= w.start && it < w.end }
            inside += m.size
            res.add(FartWindow(w, m.size, d.size, matchCount(m, d, tolMs)))
        }
        return FartComparison(res, man.size - inside)
    }

    /** Dopasowanie 1:1 – najpierw najbliższe pary (zachłannie po odległości w czasie). */
    fun matchCount(a: List<Long>, b: List<Long>, tolMs: Long): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val pairs = ArrayList<Triple<Long, Int, Int>>()
        var start = 0
        for (i in a.indices) {
            while (start < b.size && b[start] < a[i] - tolMs) start++
            var j = start
            while (j < b.size && b[j] <= a[i] + tolMs) {
                pairs.add(Triple(abs(a[i] - b[j]), i, j))
                j++
            }
        }
        pairs.sortBy { it.first }
        val usedA = BooleanArray(a.size)
        val usedB = BooleanArray(b.size)
        var n = 0
        for ((_, i, j) in pairs) {
            if (usedA[i] || usedB[j]) continue
            usedA[i] = true
            usedB[j] = true
            n++
        }
        return n
    }
}

/* ---------- AFIB ↔ migotanie w EKG ---------- */

data class AfDay(val day: String, val ecgMs: Long, val kreskaMs: Long, val bothMs: Long)

data class AfComparison(
    val ecgMs: Long, // migotanie wg EKG
    val kreskaMs: Long, // AFIB w Kresce (bez pauz), cały okres
    val bothMs: Long, // jednocześnie EKG i Kreska
    val kreskaOnlyInWindowsMs: Long, // wpis w Kresce, EKG nagrywało, a migotania nie widziało
    val kreskaOutsideMs: Long, // wpis w Kresce bez zapisu EKG
    val coverageMs: Long, // jak długo nagrywało EKG
    val ecgEpisodes: Int,
    val felt: Int, // epizody EKG z wpisem w Kresce (objawowe)
    val kreskaSessions: Int,
    val kreskaCovered: Int, // sesje Kreski, w czasie których nagrywało EKG
    val confirmed: Int, // … z tego potwierdzone migotaniem w EKG
    val medianDelayMs: Long?, // start wpisu w Kresce − start epizodu w EKG (potwierdzone)
    val days: List<AfDay>,
) {
    val ecgOnlyMs: Long get() = ecgMs - bothMs

    /** Najlepsze oszacowanie czasu AF: EKG tam, gdzie nagrywało, Kreska poza zapisami. */
    val combinedMs: Long get() = ecgMs + kreskaOutsideMs

    val burden: Double? get() = if (coverageMs > 0) ecgMs.toDouble() / coverageMs else null
}

object AfMerge {
    /**
     * @param ecg epizody AF z Polar EKG
     * @param windows fragmenty zapisu EKG
     * @param kreska sesje AFIB z Kreski, każda jako lista aktywnych odcinków (bez pauz)
     * @param dayOf klucz dnia (yyyy-MM-dd) dla chwili w ms
     * @param dayStarts granice dni w zakresie: start każdego dnia + koniec zakresu
     */
    fun compare(
        ecg: List<Span>,
        windows: List<Span>,
        kreska: List<List<Span>>,
        range: Span,
        dayStarts: List<Pair<String, Long>>,
    ): AfComparison {
        val cov = Spans.clip(windows, range)
        val e = Spans.intersect(Spans.clip(ecg, range), cov) // AF tylko tam, gdzie był zapis
        val sessions = kreska.map { Spans.clip(it, range) }.filter { it.isNotEmpty() }
        val k = Spans.union(sessions.flatten())
        val both = Spans.intersect(e, k)
        val kIn = Spans.intersect(k, cov)
        val kOut = Spans.subtract(k, cov)

        val episodes = Spans.union(ecg.filter { it.end > range.start && it.start < range.end })
        val felt = episodes.count { ep -> Spans.intersect(listOf(ep), k).isNotEmpty() }

        var covered = 0
        var confirmed = 0
        val delays = ArrayList<Long>()
        for (s in sessions) {
            if (Spans.intersect(s, cov).isEmpty()) continue
            covered++
            val hit = Spans.intersect(s, e)
            if (hit.isEmpty()) continue
            confirmed++
            val h = hit.first().start
            val ep = episodes.firstOrNull { it.start <= h && it.end > h }
            if (ep != null) delays.add(s.first().start - ep.start)
        }
        delays.sort()

        val days = ArrayList<AfDay>()
        for (i in 0 until dayStarts.size - 1) {
            val d = listOf(Span(dayStarts[i].second, dayStarts[i + 1].second))
            val de = Spans.total(Spans.intersect(e, d))
            val dk = Spans.total(Spans.intersect(k, d))
            if (de > 0 || dk > 0) days.add(AfDay(dayStarts[i].first, de, dk, Spans.total(Spans.intersect(both, d))))
        }

        return AfComparison(
            ecgMs = Spans.total(e),
            kreskaMs = Spans.total(k),
            bothMs = Spans.total(both),
            kreskaOnlyInWindowsMs = Spans.total(kIn) - Spans.total(both),
            kreskaOutsideMs = Spans.total(kOut),
            coverageMs = Spans.total(cov),
            ecgEpisodes = episodes.size,
            felt = felt,
            kreskaSessions = sessions.size,
            kreskaCovered = covered,
            confirmed = confirmed,
            medianDelayMs = if (delays.isEmpty()) null else delays[delays.size / 2],
            days = days.reversed(),
        )
    }
}
