package pl.kreska.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/* ---------- modele ---------- */

data class Counter(
    val id: Long,
    val name: String,
    val step: Int,
    val goal: Int?,
    val color: Int,
    val notify: Boolean,
)

data class Tracker(
    val id: Long,
    val name: String,
    val color: Int,
    val notify: Boolean,
)

data class Session(
    val id: Long,
    val trackerId: Long,
    val start: Long,
    val end: Long?,
    val note: String,
)

/** Pauza w trakcie zdarzenia; end == null oznacza, że pauza trwa. */
data class Pause(
    val id: Long,
    val sessionId: Long,
    val start: Long,
    val end: Long?,
)

/** Pojedyncza zmiana licznika. */
data class Tap(val ts: Long, val amount: Int, val manual: Boolean, val id: Long = 0L)

/* ---------- daty i liczby ---------- */

object Dates {
    val zone: ZoneId get() = ZoneId.systemDefault()
    val PL: Locale = Locale.forLanguageTag("pl-PL")
    private val hmF = DateTimeFormatter.ofPattern("HH:mm")
    private val dayF = DateTimeFormatter.ofPattern("EEE, d MMM", PL)
    private val longF = DateTimeFormatter.ofPattern("EEEE, d MMMM", PL)
    private val wdF = DateTimeFormatter.ofPattern("EEE", PL)
    private val fullF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateF = DateTimeFormatter.ofPattern("d.MM.yyyy")

    fun todayKey(): String = LocalDate.now().toString()
    fun zdt(ts: Long): ZonedDateTime = Instant.ofEpochMilli(ts).atZone(zone)
    fun keyOf(ts: Long): String = zdt(ts).toLocalDate().toString()
    fun startOf(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun hm(ts: Long): String = zdt(ts).format(hmF)
    fun full(ts: Long): String = zdt(ts).format(fullF)
    fun dateTime(ts: Long): String = zdt(ts).format(dateF) + ", " + hm(ts)
    fun day(d: LocalDate): String = d.format(dayF)
    fun dayOf(ts: Long): String = zdt(ts).format(dayF)
    fun longDay(d: LocalDate): String = d.format(longF)
    fun weekday(d: LocalDate): String = d.format(wdF)
    fun date(d: LocalDate): String = d.format(dateF)

    fun dur(ms: Long): String {
        if (ms < 60_000) return "${maxOf(0L, ms / 1000)} s"
        val m = (ms + 30_000) / 60_000
        val h = m / 60
        val r = m % 60
        return when {
            h == 0L -> "$m min"
            r == 0L -> "$h h"
            else -> "$h h $r min"
        }
    }

    fun clock(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
}

object Num {
    private val f: NumberFormat = NumberFormat.getNumberInstance(Dates.PL).apply { maximumFractionDigits = 1 }
    fun fmt(x: Number): String = f.format(x)
}

/* ---------- preferencje ---------- */

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("kreska", Context.MODE_PRIVATE)

    fun reminderOn(ctx: Context): Boolean = sp(ctx).getBoolean("rem_on", false)
    fun reminderMinutes(ctx: Context): Int = sp(ctx).getInt("rem_min", 20 * 60)
    fun overdueHours(ctx: Context): Int = sp(ctx).getInt("overdue_h", 4)
    fun goalNotify(ctx: Context): Boolean = sp(ctx).getBoolean("goal_notify", true)

    /** Parametry pokazywane w cichych powiadomieniach (klucze [NotifField]). */
    fun notifFields(ctx: Context): Set<NotifField> {
        val keys = sp(ctx).getStringSet("notif_fields", null) ?: return NotifField.defaults
        return NotifField.entries.filter { it.key in keys }.toSet()
    }

    /** Na ekranie blokady same nazwy, bez liczb i czasów. */
    fun notifHideOnLock(ctx: Context): Boolean = sp(ctx).getBoolean("notif_lock_hide", false)

    fun saveNotif(ctx: Context, fields: Set<NotifField>, hideOnLock: Boolean) {
        sp(ctx).edit()
            .putStringSet("notif_fields", fields.map { it.key }.toSet())
            .putBoolean("notif_lock_hide", hideOnLock)
            .apply()
    }

    /** Ostatni moment, w którym użytkownik korzystał z aplikacji lub jej powiadomień. */
    fun touch(ctx: Context) {
        sp(ctx).edit().putLong("last_seen", System.currentTimeMillis()).apply()
    }

    fun markRebooted(ctx: Context) {
        sp(ctx).edit().putBoolean("rebooted", true).apply()
    }

    /** Czy po uruchomieniu pokazać ekran „Witaj z powrotem”: trwają zdarzenia, a aplikacja długo nie była używana lub telefon się restartował. */
    fun consumeResumeNeeded(ctx: Context, hasRunning: Boolean): Boolean {
        val p = sp(ctx)
        val rebooted = p.getBoolean("rebooted", false)
        val lastSeen = p.getLong("last_seen", 0L)
        p.edit().putBoolean("rebooted", false).apply()
        if (!hasRunning) return false
        return rebooted || System.currentTimeMillis() - lastSeen > 30 * 60_000L
    }

    fun save(ctx: Context, reminderOn: Boolean, reminderMinutes: Int, overdueHours: Int, goalNotify: Boolean) {
        sp(ctx).edit()
            .putBoolean("rem_on", reminderOn)
            .putInt("rem_min", reminderMinutes)
            .putInt("overdue_h", overdueHours)
            .putBoolean("goal_notify", goalNotify)
            .apply()
    }
}

/* ---------- baza ---------- */

private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "kreska.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE counters(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "step INTEGER NOT NULL DEFAULT 1, goal INTEGER, color INTEGER NOT NULL DEFAULT 0, " +
                "notify INTEGER NOT NULL DEFAULT 1, sort INTEGER NOT NULL DEFAULT 0)"
        )
        // Każda zmiana licznika to osobny wiersz: +krok, −krok albo korekta ręczna (manual=1).
        db.execSQL(
            "CREATE TABLE taps(id INTEGER PRIMARY KEY AUTOINCREMENT, counter_id INTEGER NOT NULL, " +
                "ts INTEGER NOT NULL, amount INTEGER NOT NULL, day TEXT NOT NULL, manual INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX taps_cd ON taps(counter_id, day)")
        db.execSQL(
            "CREATE TABLE trackers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                "color INTEGER NOT NULL DEFAULT 0, notify INTEGER NOT NULL DEFAULT 1, sort INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, tracker_id INTEGER NOT NULL, " +
                "start INTEGER NOT NULL, end_ts INTEGER, note TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX sess_ts ON sessions(tracker_id, start)")
        createPauses(db)
        createExternal(db)
    }

    /** Dane zaimportowane z innych aplikacji (Rytm Snu, Polar EKG). */
    private fun createExternal(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ext_sources(source TEXT PRIMARY KEY, exported_at INTEGER NOT NULL, " +
                "imported_at INTEGER NOT NULL, events INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE ext_windows(source TEXT NOT NULL, start INTEGER NOT NULL, end_ts INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE ext_events(source TEXT NOT NULL, type TEXT NOT NULL, start INTEGER NOT NULL, " +
                "end_ts INTEGER, confidence REAL, verified INTEGER)"
        )
        db.execSQL("CREATE INDEX ext_ev ON ext_events(source, type, start)")
    }

    private fun createPauses(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pauses(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id INTEGER NOT NULL, " +
                "start INTEGER NOT NULL, end_ts INTEGER)"
        )
        db.execSQL("CREATE INDEX pauses_s ON pauses(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createPauses(db)
        if (oldVersion < 3) createExternal(db)
    }
}

/* ---------- repozytorium ---------- */

object Repo {
    private lateinit var app: Context
    private lateinit var helper: Db

    /** Rośnie przy każdej zmianie danych; ekrany odświeżają się, gdy się zmieni. */
    val version = MutableStateFlow(0L)

    fun init(ctx: Context) {
        if (::helper.isInitialized) return
        app = ctx.applicationContext
        helper = Db(app)
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** Spójna kopia pliku bazy (do kopii zapasowej). */
    @Synchronized
    fun snapshot(): ByteArray {
        try {
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        } catch (_: Exception) {
        }
        return app.getDatabasePath("kreska.db").readBytes()
    }

    /** Podmiana całej bazy (przywracanie kopii). Obecna zostaje obok jako zapas. */
    @Synchronized
    fun replaceDatabase(bytes: ByteArray) {
        helper.close()
        val f = app.getDatabasePath("kreska.db")
        if (f.exists()) f.copyTo(java.io.File(f.path + ".przed-przywroceniem"), overwrite = true)
        listOf("-wal", "-shm", "-journal").forEach { java.io.File(f.path + it).delete() }
        f.writeBytes(bytes)
        helper = Db(app)
        changed()
    }

    fun bump() {
        version.value = version.value + 1
    }

    private fun changed() {
        bump()
        Notifier.refresh(app)
    }

    private fun args(vararg a: Any): Array<String> = a.map { it.toString() }.toTypedArray()
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)
    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)

    /* --- liczniki --- */

    private const val CCOLS = "id,name,step,goal,color,notify"
    private fun readCounter(c: Cursor) =
        Counter(c.getLong(0), c.getString(1), c.getInt(2), c.intOrNull(3), c.getInt(4), c.getInt(5) == 1)

    fun counters(): List<Counter> =
        db.rawQuery("SELECT $CCOLS FROM counters ORDER BY sort, id", null).use { c ->
            val out = ArrayList<Counter>()
            while (c.moveToNext()) out.add(readCounter(c))
            out
        }

    fun counter(id: Long): Counter? =
        db.rawQuery("SELECT $CCOLS FROM counters WHERE id=?", args(id)).use { c ->
            if (c.moveToFirst()) readCounter(c) else null
        }

    fun saveCounter(c: Counter): Long {
        val cv = ContentValues().apply {
            put("name", c.name)
            put("step", c.step)
            if (c.goal == null) putNull("goal") else put("goal", c.goal)
            put("color", c.color)
            put("notify", if (c.notify) 1 else 0)
        }
        val id = if (c.id == 0L) db.insert("counters", null, cv) else {
            db.update("counters", cv, "id=?", args(c.id)); c.id
        }
        changed()
        return id
    }

    fun deleteCounter(id: Long) {
        db.delete("taps", "counter_id=?", args(id))
        db.delete("counters", "id=?", args(id))
        changed()
    }

    fun total(counterId: Long, day: String): Int =
        db.rawQuery("SELECT COALESCE(SUM(amount),0) FROM taps WHERE counter_id=? AND day=?", args(counterId, day)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun todayTotal(counterId: Long): Int = total(counterId, Dates.todayKey())

    fun lastTap(counterId: Long): Long? =
        db.rawQuery(
            "SELECT MAX(ts) FROM taps WHERE counter_id=? AND day=? AND amount>0 AND manual=0",
            args(counterId, Dates.todayKey())
        ).use { c -> if (c.moveToFirst()) c.longOrNull(0) else null }

    private fun insertTap(counterId: Long, ts: Long, amount: Int, day: String, manual: Boolean = false) {
        db.insert("taps", null, ContentValues().apply {
            put("counter_id", counterId)
            put("ts", ts)
            put("amount", amount)
            put("day", day)
            put("manual", if (manual) 1 else 0)
        })
    }

    fun increment(counterId: Long): Int {
        val c = counter(counterId) ?: return 0
        val before = todayTotal(counterId)
        insertTap(counterId, System.currentTimeMillis(), c.step, Dates.todayKey())
        val after = before + c.step
        val goal = c.goal
        if (goal != null && before < goal && after >= goal) Notifier.goalReached(app, c, after)
        changed()
        return after
    }

    /**
     * Cofa ostatnie dzisiejsze kliknięcie: usuwa wpis, żeby lista wpisów i statystyki godzinowe
     * były czyste. Gdy ostatni wpis nie pasuje (np. ręczna korekta), dopisuje wpis ujemny.
     */
    fun decrement(counterId: Long): Int {
        val c = counter(counterId) ?: return 0
        val before = todayTotal(counterId)
        if (before <= 0) return before
        val last = db.rawQuery(
            "SELECT id, amount FROM taps WHERE counter_id=? AND day=? ORDER BY ts DESC, id DESC LIMIT 1",
            args(counterId, Dates.todayKey())
        ).use { cur -> if (cur.moveToFirst()) cur.getLong(0) to cur.getInt(1) else null }
        val amount: Int
        if (last != null && last.second > 0 && last.second <= before) {
            db.delete("taps", "id=?", args(last.first))
            amount = last.second
        } else {
            amount = minOf(c.step, before)
            insertTap(counterId, System.currentTimeMillis(), -amount, Dates.todayKey())
        }
        changed()
        return before - amount
    }

    /* --- pojedyncze wpisy licznika (dodawanie i edycja ręczna) --- */

    fun tapsForDay(counterId: Long, day: LocalDate): List<Tap> =
        db.rawQuery(
            "SELECT ts, amount, manual, id FROM taps WHERE counter_id=? AND day=? ORDER BY ts DESC, id DESC",
            args(counterId, day)
        ).use { c ->
            val out = ArrayList<Tap>()
            while (c.moveToNext()) out.add(Tap(c.getLong(0), c.getInt(1), c.getInt(2) == 1, c.getLong(3)))
            out
        }

    /** Dopisuje wystąpienie o podanej godzinie (liczy się też do statystyk godzinowych). */
    fun addTap(counterId: Long, ts: Long, amount: Int) {
        insertTap(counterId, ts, amount, Dates.keyOf(ts))
        changed()
    }

    fun updateTap(id: Long, ts: Long, amount: Int) {
        db.update("taps", ContentValues().apply {
            put("ts", ts)
            put("amount", amount)
            put("day", Dates.keyOf(ts))
            put("manual", 0)
        }, "id=?", args(id))
        changed()
    }

    fun deleteTap(id: Long) {
        db.delete("taps", "id=?", args(id))
        changed()
    }

    /** Ustawia wynik wybranego dnia (np. gdy zapomnisz coś policzyć). */
    fun setDayValue(counterId: Long, day: LocalDate, value: Int) {
        val key = day.toString()
        val diff = value - total(counterId, key)
        if (diff == 0) return
        val ts = if (day == LocalDate.now()) System.currentTimeMillis() else Dates.startOf(day) + 12 * 3_600_000L
        insertTap(counterId, ts, diff, key, manual = true)
        changed()
    }

    fun dailyTotals(counterId: Long, from: LocalDate, to: LocalDate): Map<String, Int> =
        db.rawQuery(
            "SELECT day, SUM(amount) FROM taps WHERE counter_id=? AND day BETWEEN ? AND ? GROUP BY day",
            args(counterId, from, to)
        ).use { c ->
            val out = HashMap<String, Int>()
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    fun taps(counterId: Long, from: LocalDate, to: LocalDate): List<Tap> =
        db.rawQuery(
            "SELECT ts, amount, manual, id FROM taps WHERE counter_id=? AND day BETWEEN ? AND ? ORDER BY ts",
            args(counterId, from, to)
        ).use { c ->
            val out = ArrayList<Tap>()
            while (c.moveToNext()) out.add(Tap(c.getLong(0), c.getInt(1), c.getInt(2) == 1, c.getLong(3)))
            out
        }

    /** Pierwszy dzień z jakimkolwiek wpisem licznika (początek śledzenia). */
    fun firstDay(counterId: Long): LocalDate? =
        db.rawQuery("SELECT MIN(day) FROM taps WHERE counter_id=?", args(counterId)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) LocalDate.parse(c.getString(0)) else null
        }

    /** Dzień pierwszego zdarzenia danego typu. */
    fun firstSessionDay(trackerId: Long): LocalDate? =
        db.rawQuery("SELECT MIN(start) FROM sessions WHERE tracker_id=?", args(trackerId)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) Dates.zdt(c.getLong(0)).toLocalDate() else null
        }

    /** Wszystkie dni (cała historia) z wynikiem dziennym — do liczenia serii. */
    fun allDailyTotals(counterId: Long): Map<String, Int> =
        db.rawQuery("SELECT day, SUM(amount) FROM taps WHERE counter_id=? GROUP BY day", args(counterId)).use { c ->
            val out = HashMap<String, Int>()
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    /** Wszystkie zdarzenia danego typu (cała historia) — do serii. */
    fun allSessions(trackerId: Long): List<Session> =
        sessionsQuery("WHERE tracker_id=? ORDER BY start", args(trackerId))

    /** Suma kliknięć wg godziny doby (bez korekt ręcznych). */
    fun hourly(counterId: Long, from: LocalDate, to: LocalDate): IntArray {
        val h = IntArray(24)
        db.rawQuery(
            "SELECT ts, amount FROM taps WHERE counter_id=? AND day BETWEEN ? AND ? AND manual=0",
            args(counterId, from, to)
        ).use { c ->
            while (c.moveToNext()) {
                val hour = Dates.zdt(c.getLong(0)).hour
                h[hour] += c.getInt(1)
            }
        }
        for (i in h.indices) if (h[i] < 0) h[i] = 0
        return h
    }

    /* --- zdarzenia --- */

    private const val TCOLS = "id,name,color,notify"
    private fun readTracker(c: Cursor) = Tracker(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) == 1)

    fun trackers(): List<Tracker> =
        db.rawQuery("SELECT $TCOLS FROM trackers ORDER BY sort, id", null).use { c ->
            val out = ArrayList<Tracker>()
            while (c.moveToNext()) out.add(readTracker(c))
            out
        }

    fun tracker(id: Long): Tracker? =
        db.rawQuery("SELECT $TCOLS FROM trackers WHERE id=?", args(id)).use { c ->
            if (c.moveToFirst()) readTracker(c) else null
        }

    fun saveTracker(t: Tracker): Long {
        val cv = ContentValues().apply {
            put("name", t.name)
            put("color", t.color)
            put("notify", if (t.notify) 1 else 0)
        }
        val id = if (t.id == 0L) db.insert("trackers", null, cv) else {
            db.update("trackers", cv, "id=?", args(t.id)); t.id
        }
        changed()
        return id
    }

    fun deleteTracker(id: Long) {
        sessionsQuery("WHERE tracker_id=?", args(id)).forEach {
            Alarms.cancelOverdue(app, it.id)
            Notifier.cancelOverdue(app, it.id)
        }
        db.delete("pauses", "session_id IN (SELECT id FROM sessions WHERE tracker_id=?)", args(id))
        db.delete("sessions", "tracker_id=?", args(id))
        db.delete("trackers", "id=?", args(id))
        changed()
    }

    private const val SCOLS = "id,tracker_id,start,end_ts,note"
    private fun readSession(c: Cursor) =
        Session(c.getLong(0), c.getLong(1), c.getLong(2), c.longOrNull(3), c.getString(4))

    private fun sessionsQuery(where: String, a: Array<String>?): List<Session> =
        db.rawQuery("SELECT $SCOLS FROM sessions $where", a).use { c ->
            val out = ArrayList<Session>()
            while (c.moveToNext()) out.add(readSession(c))
            out
        }

    fun session(id: Long): Session? = sessionsQuery("WHERE id=?", args(id)).firstOrNull()
    fun running(): List<Session> = sessionsQuery("WHERE end_ts IS NULL ORDER BY start", null)
    fun runningFor(trackerId: Long): Session? =
        sessionsQuery("WHERE end_ts IS NULL AND tracker_id=? ORDER BY start DESC LIMIT 1", args(trackerId)).firstOrNull()

    fun sessions(trackerId: Long, fromTs: Long): List<Session> =
        sessionsQuery("WHERE tracker_id=? AND start>=? ORDER BY start DESC", args(trackerId, fromTs))

    fun recentSessions(limit: Int): List<Session> = sessionsQuery("ORDER BY start DESC LIMIT $limit", null)

    fun lastFinished(trackerId: Long): Session? =
        sessionsQuery("WHERE tracker_id=? AND end_ts IS NOT NULL ORDER BY start DESC LIMIT 1", args(trackerId)).firstOrNull()

    fun startSession(trackerId: Long) {
        if (tracker(trackerId) == null || runningFor(trackerId) != null) return
        val start = System.currentTimeMillis()
        val id = db.insert("sessions", null, ContentValues().apply {
            put("tracker_id", trackerId)
            put("start", start)
            putNull("end_ts")
            put("note", "")
        })
        Alarms.scheduleOverdue(app, id, 0L)
        changed()
    }

    fun stopSession(sessionId: Long) {
        val now = System.currentTimeMillis()
        db.update(
            "pauses",
            ContentValues().apply { put("end_ts", now) },
            "session_id=? AND end_ts IS NULL",
            args(sessionId)
        )
        db.update(
            "sessions",
            ContentValues().apply { put("end_ts", System.currentTimeMillis()) },
            "id=? AND end_ts IS NULL",
            args(sessionId)
        )
        Alarms.cancelOverdue(app, sessionId)
        Notifier.cancelOverdue(app, sessionId)
        changed()
    }

    fun stopTracker(trackerId: Long) {
        runningFor(trackerId)?.let { stopSession(it.id) }
    }

    /* --- pauzy --- */

    private fun readPause(c: Cursor) = Pause(c.getLong(0), c.getLong(1), c.getLong(2), c.longOrNull(3))

    fun pauses(sessionId: Long): List<Pause> =
        db.rawQuery("SELECT id,session_id,start,end_ts FROM pauses WHERE session_id=? ORDER BY start", args(sessionId)).use { c ->
            val out = ArrayList<Pause>()
            while (c.moveToNext()) out.add(readPause(c))
            out
        }

    /** Pauzy dla wielu zdarzeń naraz: mapa id zdarzenia → lista pauz. */
    fun pausesFor(sessionIds: Collection<Long>): Map<Long, List<Pause>> {
        if (sessionIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<Pause>>()
        sessionIds.chunked(500).forEach { chunk ->
            val inList = chunk.joinToString(",")
            db.rawQuery("SELECT id,session_id,start,end_ts FROM pauses WHERE session_id IN ($inList) ORDER BY start", null).use { c ->
                while (c.moveToNext()) {
                    val p = readPause(c)
                    out.getOrPut(p.sessionId) { ArrayList() }.add(p)
                }
            }
        }
        return out
    }

    fun isPaused(sessionId: Long): Boolean =
        db.rawQuery("SELECT COUNT(*) FROM pauses WHERE session_id=? AND end_ts IS NULL", args(sessionId)).use { c ->
            c.moveToFirst() && c.getInt(0) > 0
        }

    /** Czas aktywny zdarzenia (bez pauz), w milisekundach. */
    fun activeMs(s: Session): Long = Stats.activeMs(s, pauses(s.id), System.currentTimeMillis())

    fun pauseSession(sessionId: Long) {
        val s = session(sessionId) ?: return
        if (s.end != null || isPaused(sessionId)) return
        db.insert("pauses", null, ContentValues().apply {
            put("session_id", sessionId)
            put("start", System.currentTimeMillis())
            putNull("end_ts")
        })
        Alarms.cancelOverdue(app, sessionId)
        changed()
    }

    fun resumeSession(sessionId: Long) {
        val s = session(sessionId) ?: return
        if (s.end != null) return
        db.update(
            "pauses",
            ContentValues().apply { put("end_ts", System.currentTimeMillis()) },
            "session_id=? AND end_ts IS NULL",
            args(sessionId)
        )
        Alarms.scheduleOverdue(app, sessionId, activeMs(s))
        changed()
    }

    fun pauseTracker(trackerId: Long) {
        runningFor(trackerId)?.let { pauseSession(it.id) }
    }

    fun resumeTracker(trackerId: Long) {
        runningFor(trackerId)?.let { resumeSession(it.id) }
    }

    fun clearPauses(sessionId: Long) {
        db.delete("pauses", "session_id=?", args(sessionId))
        val s = session(sessionId)
        if (s != null && s.end == null) Alarms.scheduleOverdue(app, sessionId, activeMs(s))
        changed()
    }

    fun saveSession(s: Session): Long {
        val cv = ContentValues().apply {
            put("tracker_id", s.trackerId)
            put("start", s.start)
            if (s.end == null) putNull("end_ts") else put("end_ts", s.end)
            put("note", s.note)
        }
        val id = if (s.id == 0L) db.insert("sessions", null, cv) else {
            db.update("sessions", cv, "id=?", args(s.id)); s.id
        }
        if (s.end == null) {
            val saved = s.copy(id = id)
            if (isPaused(id)) Alarms.cancelOverdue(app, id) else Alarms.scheduleOverdue(app, id, activeMs(saved))
        } else {
            val endTs: Long = s.end ?: System.currentTimeMillis()
            db.update(
                "pauses",
                ContentValues().apply { put("end_ts", endTs) },
                "session_id=? AND end_ts IS NULL",
                args(id)
            )
            Alarms.cancelOverdue(app, id)
            Notifier.cancelOverdue(app, id)
        }
        changed()
        return id
    }

    fun deleteSession(id: Long) {
        Alarms.cancelOverdue(app, id)
        Notifier.cancelOverdue(app, id)
        db.delete("pauses", "session_id=?", args(id))
        db.delete("sessions", "id=?", args(id))
        changed()
    }

    /* --- dane z innych aplikacji --- */

    /** Zastępuje wszystkie dane danego źródła (plik eksportu zawiera całą historię). */
    fun replaceExternal(x: ExtImport) {
        val d = db
        d.beginTransaction()
        try {
            d.delete("ext_windows", "source=?", args(x.source))
            d.delete("ext_events", "source=?", args(x.source))
            for (w in x.windows) {
                d.insert("ext_windows", null, ContentValues().apply {
                    put("source", x.source); put("start", w.start); put("end_ts", w.end)
                })
            }
            for (e in x.events) {
                d.insert("ext_events", null, ContentValues().apply {
                    put("source", x.source)
                    put("type", e.type)
                    put("start", e.start)
                    if (e.end == null) putNull("end_ts") else put("end_ts", e.end)
                    if (e.confidence == null) putNull("confidence") else put("confidence", e.confidence)
                    if (e.verified == null) putNull("verified") else put("verified", if (e.verified) 1 else 0)
                })
            }
            d.insertWithOnConflict("ext_sources", null, ContentValues().apply {
                put("source", x.source)
                put("exported_at", x.exportedAt)
                put("imported_at", System.currentTimeMillis())
                put("events", x.events.size)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        bump()
    }

    /** Źródło → (czas eksportu, liczba zdarzeń). */
    fun extSources(): Map<String, Pair<Long, Int>> =
        db.rawQuery("SELECT source, exported_at, events FROM ext_sources", null).use { c ->
            val out = HashMap<String, Pair<Long, Int>>()
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1) to c.getInt(2)
            out
        }

    fun extWindows(source: String): List<Span> =
        db.rawQuery("SELECT start, end_ts FROM ext_windows WHERE source=? ORDER BY start", args(source)).use { c ->
            val out = ArrayList<Span>()
            while (c.moveToNext()) out.add(Span(c.getLong(0), c.getLong(1)))
            out
        }

    fun extEvents(source: String, type: String): List<ExtEvent> =
        db.rawQuery(
            "SELECT type, start, end_ts, confidence, verified FROM ext_events WHERE source=? AND type=? ORDER BY start",
            args(source, type)
        ).use { c ->
            val out = ArrayList<ExtEvent>()
            while (c.moveToNext()) {
                out.add(
                    ExtEvent(
                        c.getString(0), c.getLong(1), c.longOrNull(2),
                        if (c.isNull(3)) null else c.getDouble(3),
                        c.intOrNull(4)?.let { it == 1 },
                    )
                )
            }
            out
        }

    /** Zdarzenia typu, które nachodzą na przedział [fromTs, toTs). */
    fun sessionsOverlapping(trackerId: Long, fromTs: Long, toTs: Long): List<Session> =
        sessionsQuery(
            "WHERE tracker_id=? AND start<? AND (end_ts IS NULL OR end_ts>?) ORDER BY start",
            args(trackerId, toTs, fromTs)
        )

    /* --- eksport --- */

    fun csv(from: LocalDate): String {
        val to = LocalDate.now()
        val sb = StringBuilder("﻿typ;nazwa;data;wartość;początek;koniec;czas_min;pauzy_min;notatka\r\n")
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        for (c in counters()) {
            dailyTotals(c.id, from, to).toSortedMap().forEach { (d, v) ->
                if (v != 0) sb.append("licznik;${q(c.name)};$d;$v;;;;;\r\n")
            }
        }
        val names = trackers().associate { it.id to it.name }
        val list = sessionsQuery("WHERE start>=? ORDER BY start", args(Dates.startOf(from)))
        val pauseMap = pausesFor(list.map { it.id })
        val now = System.currentTimeMillis()
        for (s in list) {
            val n = names[s.trackerId] ?: continue
            val end = s.end
            val ps = pauseMap[s.id] ?: emptyList()
            val active = Stats.activeMs(s, ps, now)
            val paused = (end ?: now) - s.start - active
            sb.append("zdarzenie;${q(n)};${Dates.keyOf(s.start)};;${Dates.full(s.start)};")
            sb.append(if (end != null) Dates.full(end) else "").append(';')
            sb.append(active / 60_000).append(';')
            sb.append(paused / 60_000).append(';')
            sb.append(q(s.note)).append("\r\n")
        }
        return sb.toString()
    }
}
