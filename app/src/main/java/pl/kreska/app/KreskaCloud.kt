package pl.kreska.app

import android.content.Context
import org.json.JSONObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pl.rafal.koofr.KoofrApp
import pl.rafal.koofr.KoofrExchange
import pl.rafal.koofr.KoofrSync
import pl.rafal.koofr.KsException
import java.nio.ByteBuffer

/** Kopia Kreski na rodzinnym Koofr: plik bazy + ustawienia, zaszyfrowane na telefonie. */
object KreskaCloud : KoofrApp {
    override val appId = "kreska"
    override val appName = "Kreska"
    override val contentNote = "W kopii są liczniki, zdarzenia, wszystkie wpisy, dane z innych aplikacji i ustawienia."
    override val busy: Boolean get() = false

    override val exchange: KoofrExchange = KreskaExchange

    override suspend fun export(ctx: Context): Pair<ByteArray, List<KoofrSync.Blob>> {
        val prefs = JSONObject()
        ctx.getSharedPreferences("kreska", Context.MODE_PRIVATE).all.forEach { (k, v) ->
            when (v) {
                is Set<*> -> prefs.put(k, JSONObject().put("set", org.json.JSONArray(v.map { it.toString() })))
                null -> {}
                else -> prefs.put(k, JSONObject().put(typeOf(v), v))
            }
        }
        val header = JSONObject().put("format", "kreska-kopia").put("version", 1).put("prefs", prefs).toString().toByteArray()
        val db = Repo.snapshot()
        return (ByteBuffer.allocate(4).putInt(header.size).array() + header + db) to emptyList()
    }

    private fun typeOf(v: Any) = when (v) {
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Float -> "f"
        else -> "s"
    }

    override suspend fun import(ctx: Context, r: KoofrSync.Restored, progress: (String) -> Unit) {
        val d = r.data
        if (d.size < 4) throw KsException("Kopia jest uszkodzona.")
        val len = ByteBuffer.wrap(d, 0, 4).int
        val header = JSONObject(String(d, 4, len))
        if (header.optString("format") != "kreska-kopia") throw KsException("To nie jest kopia Kreski.")
        val db = d.copyOfRange(4 + len, d.size)
        if (db.size < 100 || String(db, 0, 15) != "SQLite format 3") throw KsException("Kopia nie zawiera bazy Kreski.")
        progress("Zapisywanie…")
        Repo.replaceDatabase(db)
        val e = ctx.getSharedPreferences("kreska", Context.MODE_PRIVATE).edit()
        header.optJSONObject("prefs")?.let { p ->
            p.keys().forEach { k ->
                val o = p.getJSONObject(k)
                when {
                    o.has("set") -> e.putStringSet(k, (0 until o.getJSONArray("set").length()).map { o.getJSONArray("set").getString(it) }.toSet())
                    o.has("b") -> e.putBoolean(k, o.getBoolean("b"))
                    o.has("i") -> e.putInt(k, o.getInt("i"))
                    o.has("l") -> e.putLong(k, o.getLong("l"))
                    o.has("f") -> e.putFloat(k, o.getDouble("f").toFloat())
                    o.has("s") -> e.putString(k, o.getString("s"))
                }
            }
        }
        e.apply()
        Notifier.refresh(ctx)
        Alarms.scheduleAll(ctx)
    }
}


/** Wymiana przez Koofr: pobiera wiatry z Rytmu Snu i epizody AF z Polar EKG, udostępnia wybrane liczniki. */
object KreskaExchange : KoofrExchange {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("kreska", Context.MODE_PRIVATE)

    override val publishes = "Wybrane liczniki z godzinami kliknięć (np. FF) i zdarzenia (np. AFIB) — dla Dziennika talerza i Doby"
    override val sources = listOf(
        SRC_NIGHT_NEW to "Rytm Snu (FF w nocy)",
        SRC_ECG to "Polar EKG (epizody AF)",
    )

    fun fileName(c: Counter) = "kreska-" + pl.rafal.koofr.KoofrSync.slugify(c.name)

    /** Udostępniane liczniki; domyślnie licznik „FF”. */
    fun shared(ctx: Context, counters: List<Counter>): Set<Long> {
        val raw = sp(ctx).getStringSet("ex_counters", null)
            ?: return counters.filter { it.name.trim().equals("FF", true) }.map { it.id }.toSet()
        return raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun setShared(ctx: Context, ids: Set<Long>) = sp(ctx).edit().putStringSet("ex_counters", ids.map { it.toString() }.toSet()).apply()

    fun trackerFile(t: Tracker) = "kreska-z-" + pl.rafal.koofr.KoofrSync.slugify(t.name)

    /** Udostępniane zdarzenia; domyślnie zdarzenie porównywane z migotaniem (AFIB). */
    fun sharedTrackers(ctx: Context, trackers: List<Tracker>): Set<Long> {
        val raw = sp(ctx).getStringSet("ex_trackers", null)
            ?: return setOfNotNull(ComparePrefs.trackerId(ctx, trackers))
        return raw.mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun setSharedTrackers(ctx: Context, ids: Set<Long>) =
        sp(ctx).edit().putStringSet("ex_trackers", ids.map { it.toString() }.toSet()).apply()

    /**
     * Zdarzenie jako plik „wymiana-zdarzen” (źródło "kreska", "counter" = nazwa zdarzenia):
     * events = aktywne odcinki (bez pauz, type "session"), day_totals = liczba zdarzeń w dniu startu.
     */
    fun buildTracker(t: Tracker): ByteArray {
        val now = System.currentTimeMillis()
        val sessions = Repo.allSessions(t.id).sortedBy { it.start }
        val pauses = Repo.pausesFor(sessions.map { it.id })
        val events = org.json.JSONArray()
        val perDay = sortedMapOf<String, Int>()
        for (s in sessions) {
            val active = Spans.subtract(listOf(Span(s.start, s.end ?: now)),
                Spans.union(pauses[s.id].orEmpty().map { Span(it.start, it.end ?: now) }))
            active.forEachIndexed { i, a ->
                events.put(JSONObject().put("id", "t${s.id}-$i").put("type", "session").put("start", a.start).put("end", a.end))
            }
            val d = Dates.keyOf(s.start)
            perDay[d] = (perDay[d] ?: 0) + 1
        }
        val days = org.json.JSONArray()
        perDay.forEach { (d, n) -> days.put(JSONObject().put("date", d).put("total", n)) }
        val windows = org.json.JSONArray()
        sessions.firstOrNull()?.let { windows.put(JSONObject().put("start", Dates.startOf(Dates.zdt(it.start).toLocalDate())).put("end", now)) }
        return JSONObject()
            .put("format", "wymiana-zdarzen").put("version", 1).put("source", "kreska").put("exported_at", now)
            .put("counter", t.name).put("kind", "tracker")
            .put("windows", windows).put("events", events).put("day_totals", days)
            .toString().toByteArray(Charsets.UTF_8)
    }

    override suspend fun export(ctx: Context): Map<String, ByteArray?> {
        val counters = Repo.counters()
        val on = shared(ctx, counters)
        val out = LinkedHashMap<String, ByteArray?>()
        val prev = sp(ctx).getStringSet("ex_published", emptySet()) ?: emptySet()
        for (c in counters) {
            if (c.id in on) out[fileName(c)] = KreskaExport.build(c).json.toByteArray(Charsets.UTF_8)
        }
        val trackers = Repo.trackers()
        val onT = sharedTrackers(ctx, trackers)
        for (t in trackers) if (t.id in onT) out[trackerFile(t)] = buildTracker(t)
        // wcześniej udostępniane, a teraz odznaczone lub usunięte — skasować z Koofr
        for (n in prev) if (n !in out) out[n] = null
        sp(ctx).edit().putStringSet("ex_published", out.filterValues { it != null }.keys).apply()
        return out
    }

    override suspend fun import(ctx: Context, name: String, data: ByteArray) {
        Repo.replaceExternal(Exchange.parse(String(data, Charsets.UTF_8)))
    }

    @Composable
    override fun ExtraSettings() {
        val ctx = LocalContext.current
        val counters = remember { Repo.counters() }
        val trackers = remember { Repo.trackers() }
        var on by remember { mutableStateOf(shared(ctx, counters)) }
        var onT by remember { mutableStateOf(sharedTrackers(ctx, trackers)) }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            if (trackers.isNotEmpty()) {
                Text("Udostępniane zdarzenia", style = MaterialTheme.typography.labelLarge)
                trackers.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable {
                        onT = if (t.id in onT) onT - t.id else onT + t.id
                        setSharedTrackers(ctx, onT)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = t.id in onT, onCheckedChange = { v ->
                            onT = if (v) onT + t.id else onT - t.id
                            setSharedTrackers(ctx, onT)
                        })
                        Text(t.name)
                    }
                }
            }
            if (counters.isEmpty()) return@Column
            Text("Udostępniane liczniki", style = MaterialTheme.typography.labelLarge)
            counters.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable {
                    on = if (c.id in on) on - c.id else on + c.id
                    setShared(ctx, on)
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = c.id in on, onCheckedChange = { v ->
                        on = if (v) on + c.id else on - c.id
                        setShared(ctx, on)
                    })
                    Text(c.name)
                }
            }
        }
    }
}
