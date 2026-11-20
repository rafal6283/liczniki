package pl.kreska.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Kontrakt wymiany (WYMIANA.md w repozytorium Rytmu Snu): każdy plik, który Kreska pobiera
 * (przedrostki z KreskaExchange.sources), musi się wczytać bez wyjątku. Przykłady z folderu `wymiana` (pliki .json)
 * pobiera CI z rafal6283/nocny-licznik do src/test/resources/wymiana.
 */
class WymianaTest {
    private val dir = File("src/test/resources/wymiana")

    private fun samples(): Map<String, String> {
        assumeTrue("brak przykładów wymiany (pobiera je CI)", dir.isDirectory)
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.associate { it.name.removeSuffix(".json") to it.readText() }
    }

    private fun pulls(name: String) = KreskaExchange.sources.any { (p, _) -> name == p || name.startsWith("$p-") }

    @Test
    fun kazdyPobieranyPlikSieWczytuje() {
        val s = samples()
        val pulled = s.keys.filter(::pulls).sorted()
        assertEquals(listOf("polar-ekg", "rytm-snu", "rytm-snu-rytm"), pulled)
        for (n in pulled) Exchange.parse(s.getValue(n)) // nie może rzucić
    }

    @Test
    fun rytmZOpaskiToOsobneZrodlo() {
        val x = Exchange.parse(samples().getValue("rytm-snu-rytm"))
        assertEquals(SRC_NIGHT_RHYTHM, x.source)
        assertTrue(x.events.all { it.type == "af" && it.end != null })
    }

    @Test
    fun rytmSnuMaDawnaNazweWBazie() {
        val x = Exchange.parse(samples().getValue("rytm-snu"))
        assertEquals(SRC_NIGHT, x.source)
        assertEquals(2, x.events.count { it.type == "fart" })
        assertEquals(null, x.events[1].verified)
    }

    @Test
    fun nieznaneZrodloToCzytelnyBlad() {
        val e = runCatching { Exchange.parse("""{"format":"wymiana-zdarzen","version":1,"source":"doba"}""") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        val v = runCatching { Exchange.parse("""{"format":"wymiana-zdarzen","version":2,"source":"polar-ekg"}""") }.exceptionOrNull()
        assertTrue(v?.message.orEmpty().contains("wersja"))
    }
}
