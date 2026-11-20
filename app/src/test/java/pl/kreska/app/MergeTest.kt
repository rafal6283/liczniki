package pl.kreska.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MergeTest {
    private val min = 60_000L

    @Test
    fun spansUnionIntersectSubtract() {
        val a = listOf(Span(0, 10), Span(5, 20), Span(30, 40))
        assertEquals(listOf(Span(0, 20), Span(30, 40)), Spans.union(a))
        assertEquals(listOf(Span(15, 20), Span(30, 35)), Spans.intersect(Spans.union(a), listOf(Span(15, 35))))
        assertEquals(listOf(Span(0, 5), Span(12, 20)), Spans.subtract(listOf(Span(0, 20)), listOf(Span(5, 12))))
        assertEquals(30L, Spans.total(Spans.union(a)))
        assertEquals(listOf(Span(10, 20)), Spans.clip(a, Span(10, 25)))
    }

    @Test
    fun ffDopasowanieJedenDoJednego() {
        // dwa kliknięcia blisko jednego wykrycia: tylko jedna para
        assertEquals(1, FartMerge.matchCount(listOf(100L, 110L), listOf(105L), 20))
        // poza tolerancją: brak pary
        assertEquals(0, FartMerge.matchCount(listOf(100L), listOf(200L), 20))
        // zachłannie po najmniejszej odległości
        assertEquals(2, FartMerge.matchCount(listOf(100L, 130L), listOf(95L, 125L), 30))
    }

    @Test
    fun ffBezPodwojnegoLiczenia() {
        val night = Span(0, 8 * 60 * min)
        val manual = listOf(10 * min, 20 * min, 9 * 60 * min) // ostatnie w dzień (poza nocą)
        val detected = listOf(10 * min + 5_000, 50 * min)
        val c = FartMerge.compare(manual, detected, listOf(night), Span(0, 24 * 60 * min), 30_000)
        assertEquals(2, c.manual)
        assertEquals(2, c.detected)
        assertEquals(1, c.matched)
        assertEquals(1, c.manualOutside)
        assertEquals(4, c.combined) // para + po jednym z każdej strony + dzienny wpis
    }

    @Test
    fun afPotwierdzenieIOpoznienie() {
        val windows = listOf(Span(0, 60 * min))
        val ecg = listOf(Span(10 * min, 20 * min))
        val kreska = listOf(listOf(Span(12 * min, 25 * min)), listOf(Span(90 * min, 100 * min)))
        val r = AfMerge.compare(ecg, windows, kreska, Span(0, 120 * min), listOf("d" to 0L, "end" to 120 * min))
        assertEquals(10 * min, r.ecgMs)
        assertEquals(8 * min, r.bothMs)
        assertEquals(1, r.ecgEpisodes)
        assertEquals(1, r.felt)
        assertEquals(2, r.kreskaSessions)
        assertEquals(1, r.kreskaCovered)
        assertEquals(1, r.confirmed)
        assertEquals(2 * min, r.medianDelayMs)
        assertEquals(10 * min, r.kreskaOutsideMs)
        assertEquals(20 * min, r.combinedMs)
        assertEquals(1.0 / 6, r.burden!!, 1e-9)
        assertEquals(1, r.days.size)
    }

    @Test
    fun afBezZapisuEkg() {
        val r = AfMerge.compare(emptyList(), emptyList(), listOf(listOf(Span(0, min))), Span(0, 10 * min), listOf("d" to 0L, "e" to 10 * min))
        assertNull(r.burden)
        assertNull(r.medianDelayMs)
        assertEquals(min, r.kreskaOutsideMs)
    }

    @Test
    fun czasTrwania() {
        assertEquals("45 s", Dates.dur(45_000))
        assertEquals("2 min", Dates.dur(90_000))
        assertEquals("1 h", Dates.dur(60 * min))
        assertEquals("1 h 5 min", Dates.dur(65 * min))
        assertEquals("1:01:01", Dates.clock(3_661_000))
    }
}
