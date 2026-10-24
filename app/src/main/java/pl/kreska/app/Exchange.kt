package pl.kreska.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.time.LocalDate

/*
 * Import plików „wymiana-zdarzen” v1 z Nocnego licznika i Polar EKG.
 *
 * {
 *   "format": "wymiana-zdarzen", "version": 1,
 *   "source": "nocny-licznik" | "polar-ekg", "exported_at": ms,
 *   "windows": [{"start": ms, "end": ms}],           // kiedy aplikacja faktycznie mierzyła
 *   "events":  [{"id": "...", "type": "fart" | "af", "start": ms, "end": ms?, "confidence": 0.8?, "verified": bool?}]
 * }
 * Czasy w ms od epoki (UTC). Każdy plik zawiera całą historię źródła – import zastępuje poprzedni.
 */

const val SRC_NIGHT = "nocny-licznik"
const val SRC_ECG = "polar-ekg"

data class ExtEvent(val type: String, val start: Long, val end: Long?, val confidence: Double?, val verified: Boolean?)

data class ExtImport(val source: String, val exportedAt: Long, val windows: List<Span>, val events: List<ExtEvent>)

object Exchange {
    fun sourceLabel(s: String) = when (s) {
        SRC_NIGHT -> "Nocny licznik"
        SRC_ECG -> "Polar EKG"
        else -> s
    }

    /** Parsuje plik; rzuca IllegalArgumentException z opisem po polsku, gdy to nie ten format. */
    fun parse(text: String): ExtImport {
        val j = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("To nie jest plik eksportu (niepoprawny JSON)")
        }
        if (j.optString("format") != "wymiana-zdarzen") throw IllegalArgumentException("To nie jest plik eksportu z Nocnego licznika ani Polar EKG")
        if (j.optInt("version") != 1) throw IllegalArgumentException("Nieobsługiwana wersja pliku – zaktualizuj Kreskę")
        val source = j.getString("source")
        if (source != SRC_NIGHT && source != SRC_ECG) throw IllegalArgumentException("Nieznane źródło: $source")

        val ws = j.optJSONArray("windows")
        val windows = ArrayList<Span>()
        if (ws != null) for (i in 0 until ws.length()) {
            val w = ws.getJSONObject(i)
            windows.add(Span(w.getLong("start"), w.getLong("end")))
        }
        val es = j.optJSONArray("events")
        val events = ArrayList<ExtEvent>()
        if (es != null) for (i in 0 until es.length()) {
            val e = es.getJSONObject(i)
            events.add(
                ExtEvent(
                    type = e.getString("type"),
                    start = e.getLong("start"),
                    end = if (e.has("end") && !e.isNull("end")) e.getLong("end") else null,
                    confidence = if (e.has("confidence") && !e.isNull("confidence")) e.getDouble("confidence") else null,
                    verified = if (e.has("verified") && !e.isNull("verified")) e.getBoolean("verified") else null,
                )
            )
        }
        return ExtImport(source, j.optLong("exported_at", System.currentTimeMillis()), windows, events)
    }

    /** Wczytuje plik z Uri (udostępniony lub wybrany) i zapisuje w bazie. Zwraca komunikat dla użytkownika. */
    fun importUri(ctx: Context, uri: Uri): String = try {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("Nie udało się otworzyć pliku")
        val x = parse(text)
        Repo.replaceExternal(x)
        val what = if (x.source == SRC_NIGHT) "nocy: ${x.windows.size}, wiatrów: ${x.events.count { it.verified != false }}"
        else "fragmentów zapisu: ${x.windows.size}, epizodów AF: ${x.events.size}"
        "Zaimportowano z ${sourceLabel(x.source)} – $what"
    } catch (e: IllegalArgumentException) {
        e.message ?: "Niepoprawny plik"
    } catch (e: Exception) {
        "Nie udało się zaimportować pliku"
    }
}

/* ---------- ustawienia porównania ---------- */

object ComparePrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("kreska", Context.MODE_PRIVATE)

    /** Licznik FF; domyślnie licznik o nazwie „FF”. */
    fun counterId(ctx: Context, counters: List<Counter>): Long? {
        val id = sp(ctx).getLong("cmp_counter", -1L)
        if (id >= 0) return counters.firstOrNull { it.id == id }?.id
        return counters.firstOrNull { it.name.trim().equals("FF", ignoreCase = true) }?.id
    }

    /** Typ zdarzenia AFIB; domyślnie pierwszy z „AF” w nazwie. */
    fun trackerId(ctx: Context, trackers: List<Tracker>): Long? {
        val id = sp(ctx).getLong("cmp_tracker", -1L)
        if (id >= 0) return trackers.firstOrNull { it.id == id }?.id
        return trackers.firstOrNull { it.name.contains("AF", ignoreCase = true) }?.id
    }

    fun tolSec(ctx: Context): Int = sp(ctx).getInt("cmp_tol", 60)

    fun setCounter(ctx: Context, id: Long) = sp(ctx).edit().putLong("cmp_counter", id).apply()
    fun setTracker(ctx: Context, id: Long) = sp(ctx).edit().putLong("cmp_tracker", id).apply()
    fun setTol(ctx: Context, s: Int) = sp(ctx).edit().putInt("cmp_tol", s).apply()
}

/* ---------- składanie porównań z bazy ---------- */

object Compare {
    private fun range(from: LocalDate, to: LocalDate) = Span(Dates.startOf(from), Dates.startOf(to.plusDays(1)))

    fun farts(counterId: Long, from: LocalDate, to: LocalDate, tolSec: Int): FartComparison {
        val r = range(from, to)
        // Kliknięcia z godziną; korekty dnia (manual) nie mają prawdziwej godziny, więc pomijamy.
        val manual = ArrayList<Long>()
        for (t in Repo.taps(counterId, from.minusDays(1), to.plusDays(1))) {
            if (t.manual || t.amount <= 0) continue
            repeat(t.amount) { manual.add(t.ts) }
        }
        val detected = Repo.extEvents(SRC_NIGHT, "fart").filter { it.verified != false }.map { it.start }
        return FartMerge.compare(manual, detected, Repo.extWindows(SRC_NIGHT), r, tolSec * 1000L)
    }

    fun af(trackerId: Long, from: LocalDate, to: LocalDate): AfComparison {
        val r = range(from, to)
        val now = System.currentTimeMillis()
        val sessions = Repo.sessionsOverlapping(trackerId, r.start, r.end)
        val pauses = Repo.pausesFor(sessions.map { it.id })
        val active = sessions.map { s ->
            val whole = listOf(Span(s.start, s.end ?: now))
            val ps = Spans.union(pauses[s.id].orEmpty().map { Span(it.start, it.end ?: now) })
            Spans.subtract(whole, ps)
        }
        val ecg = Repo.extEvents(SRC_ECG, "af").mapNotNull { e -> e.end?.let { Span(e.start, it) } }
        var d = from
        val starts = ArrayList<Pair<String, Long>>()
        while (!d.isAfter(to)) {
            starts.add(d.toString() to Dates.startOf(d))
            d = d.plusDays(1)
        }
        starts.add("" to r.end)
        return AfMerge.compare(ecg, Repo.extWindows(SRC_ECG), active, r, starts)
    }
}

/* ---------- eksport wpisów licznika z godzinami ---------- */

/*
 * Ten sam format „wymiana-zdarzen” v1, źródło "kreska" – czyta go m.in. Dziennik talerza.
 * {
 *   "format": "wymiana-zdarzen", "version": 1, "source": "kreska", "exported_at": ms,
 *   "counter": "FF",
 *   "windows": [{"start": ms, "end": ms}],             // od pierwszego wpisu do teraz
 *   "events":  [{"id": "k12-0", "type": "tap", "start": ms}],    // każde kliknięcie z godziną
 *   "day_totals": [{"date": "2026-10-20", "total": 7}]  // sumy dni (z ręcznymi korektami)
 * }
 * Kliknięcia „−” cofają ostatnie kliknięcie tego dnia. Korekty dnia (bez godziny) są tylko w day_totals.
 */
object KreskaExport {
    data class Result(val json: String, val events: Int, val days: Int)

    fun build(counter: Counter): Result {
        val today = LocalDate.now()
        val all = Repo.taps(counter.id, LocalDate.of(2000, 1, 1), today)
        val totals = Repo.dailyTotals(counter.id, LocalDate.of(2000, 1, 1), today)
        // zdarzenia z godziną: plusy bez korekt, minus usuwa ostatni plus z tego samego dnia
        val byDay = LinkedHashMap<String, ArrayList<Pair<Long, String>>>()
        for (t in all) {
            if (t.manual) continue
            val key = Dates.keyOf(t.ts)
            val list = byDay.getOrPut(key) { ArrayList() }
            if (t.amount > 0) {
                repeat(t.amount) { i -> list.add(t.ts to "k${t.id}-$i") }
            } else if (t.amount < 0) {
                repeat(-t.amount) { if (list.isNotEmpty()) list.removeAt(list.size - 1) }
            }
        }
        val events = org.json.JSONArray()
        var n = 0
        for ((_, list) in byDay) for ((ts, id) in list) {
            events.put(JSONObject().put("id", id).put("type", "tap").put("start", ts))
            n++
        }
        val dayArr = org.json.JSONArray()
        for ((d, v) in totals.toSortedMap()) if (v != 0) dayArr.put(JSONObject().put("date", d).put("total", v))
        val windows = org.json.JSONArray()
        val first = all.firstOrNull()?.ts
        val now = System.currentTimeMillis()
        if (first != null) windows.put(JSONObject().put("start", Dates.startOf(Dates.zdt(first).toLocalDate())).put("end", now))
        val j = JSONObject()
            .put("format", "wymiana-zdarzen")
            .put("version", 1)
            .put("source", "kreska")
            .put("exported_at", now)
            .put("counter", counter.name)
            .put("windows", windows)
            .put("events", events)
            .put("day_totals", dayArr)
        return Result(j.toString(), n, dayArr.length())
    }
}
