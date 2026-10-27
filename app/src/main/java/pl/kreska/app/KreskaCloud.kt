package pl.kreska.app

import android.content.Context
import org.json.JSONObject
import pl.rafal.koofr.KoofrApp
import pl.rafal.koofr.KoofrSync
import pl.rafal.koofr.KsException
import java.nio.ByteBuffer

/** Kopia Kreski na rodzinnym Koofr: plik bazy + ustawienia, zaszyfrowane na telefonie. */
object KreskaCloud : KoofrApp {
    override val appId = "kreska"
    override val appName = "Kreska"
    override val contentNote = "W kopii są liczniki, zdarzenia, wszystkie wpisy, dane z innych aplikacji i ustawienia."
    override val busy: Boolean get() = false

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
