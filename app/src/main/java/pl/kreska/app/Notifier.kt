package pl.kreska.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.DayOfWeek
import java.time.LocalDate

const val EXTRA_ID = "pl.kreska.app.ID"
const val ACTION_INC = "pl.kreska.app.INC"
const val ACTION_DEC = "pl.kreska.app.DEC"
const val ACTION_START = "pl.kreska.app.START"
const val ACTION_STOP = "pl.kreska.app.STOP"
const val ACTION_PAUSE = "pl.kreska.app.PAUSE"
const val ACTION_RESUME = "pl.kreska.app.RESUME"

/** Parametry, które można pokazać w cichych powiadomieniach w panelu. */
enum class NotifField(val key: String, val group: Group, val label: String) {
    C_GOAL("c_goal", Group.COUNTER, "Cel dzienny (np. 7 / 10)"),
    C_BAR("c_bar", Group.COUNTER, "Pasek postępu do celu"),
    C_LEFT("c_left", Group.COUNTER, "Ile brakuje do celu"),
    C_LAST("c_last", Group.COUNTER, "Godzina ostatniego kliknięcia"),
    C_YESTERDAY("c_yesterday", Group.COUNTER, "Wczoraj"),
    C_WEEK("c_week", Group.COUNTER, "Suma od poniedziałku"),
    C_AVG7("c_avg7", Group.COUNTER, "Średnia z 7 dni"),
    T_LAST("t_last", Group.IDLE, "Ostatnie zdarzenie (dzień, czas trwania)"),
    T_TODAY("t_today", Group.IDLE, "Dziś: ile razy i łączny czas"),
    T_AVG("t_avg", Group.IDLE, "Średni czas trwania (30 dni)"),
    R_START("r_start", Group.RUNNING, "Godzina rozpoczęcia"),
    R_CLOCK("r_clock", Group.RUNNING, "Stoper"),
    R_PAUSES("r_pauses", Group.RUNNING, "Pauzy (liczba i czas)"),
    ;

    enum class Group(val title: String) {
        COUNTER("Liczniki"),
        IDLE("Zdarzenia, gdy nie trwają"),
        RUNNING("Zdarzenia w trakcie"),
    }

    companion object {
        /** Dotychczasowy wygląd powiadomień. */
        val defaults: Set<NotifField> = setOf(C_GOAL, C_BAR, C_LAST, T_LAST, R_START, R_CLOCK, R_PAUSES)
    }
}

object Notifier {
    const val CH_PANEL = "panel"
    const val CH_RUNNING = "running"
    const val CH_REMIND = "remind"
    const val CH_GOAL = "goal"

    // Zakresy identyfikatorów powiadomień
    private const val BASE_COUNTER = 100_000
    private const val BASE_TRACKER = 200_000
    private const val BASE_OVERDUE = 300_000
    private const val BASE_GOAL = 400_000
    private const val ID_REMINDER = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(CH_PANEL, "Liczniki i zdarzenia w panelu", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Stałe powiadomienia z przyciskami +, −, Start"
                setShowBadge(false)
            },
            NotificationChannel(CH_RUNNING, "Trwające zdarzenia", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Stoper zdarzenia z przyciskiem Stop"
                setShowBadge(false)
            },
            NotificationChannel(CH_REMIND, "Przypomnienia", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Wieczorne przypomnienie o celach i o niezatrzymanych zdarzeniach"
            },
            NotificationChannel(CH_GOAL, "Osiągnięte cele", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Informacja, gdy licznik dojdzie do celu dziennego"
            },
        )
        channels.forEach { nm.createNotificationChannel(it) }
    }

    private fun canPost(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: Notification) {
        if (!canPost(ctx)) return
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    private fun openApp(ctx: Context, tab: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_TAB, tab)
        return PendingIntent.getActivity(ctx, tab, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(ctx: Context, action: String, id: Long, req: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Odświeża wszystkie stałe powiadomienia zgodnie z aktualnymi danymi. */
    fun refresh(ctx: Context) {
        val wanted = HashSet<Int>()
        val f = Prefs.notifFields(ctx)
        val hide = Prefs.notifHideOnLock(ctx)
        Repo.counters().forEachIndexed { i, c ->
            if (c.notify) {
                val id = BASE_COUNTER + c.id.toInt()
                wanted += id
                post(ctx, id, counterNotification(ctx, c, i, f, hide))
            }
        }
        val running = Repo.running().associateBy { it.trackerId }
        Repo.trackers().forEachIndexed { i, t ->
            val s = running[t.id]
            // wyłączone w powiadomieniach — nie pokazujemy nawet w trakcie (Stop w aplikacji)
            if (t.notify) {
                val id = BASE_TRACKER + t.id.toInt()
                wanted += id
                post(ctx, id, trackerNotification(ctx, t, s, i, f, hide))
            }
        }
        // Usuń powiadomienia po skasowanych lub ukrytych pozycjach.
        val nm = ctx.getSystemService(NotificationManager::class.java)
        try {
            nm.activeNotifications.forEach { sbn ->
                val id = sbn.id
                if (id >= BASE_COUNTER && id < BASE_OVERDUE && id !in wanted) nm.cancel(id)
            }
        } catch (_: Exception) {
        }
    }

    private fun counterNotification(ctx: Context, c: Counter, order: Int, f: Set<NotifField>, hide: Boolean): Notification {
        val total = Repo.todayTotal(c.id)
        val goal = c.goal
        val title = if (goal != null && NotifField.C_GOAL in f) "${c.name}: ${Num.fmt(total)} / ${Num.fmt(goal)}" else "${c.name}: ${Num.fmt(total)}"
        val parts = ArrayList<String>()
        if (NotifField.C_LAST in f) {
            val last = Repo.lastTap(c.id)
            parts += if (last != null) "ostatnio ${Dates.hm(last)}" else "dziś jeszcze nic"
        }
        if (NotifField.C_LEFT in f && goal != null) {
            parts += if (total >= goal) "cel osiągnięty" else "brakuje ${Num.fmt(goal - total)}"
        }
        val today = LocalDate.now()
        if (NotifField.C_YESTERDAY in f || NotifField.C_WEEK in f || NotifField.C_AVG7 in f) {
            val from = minOf(today.minusDays(7), today.with(DayOfWeek.MONDAY))
            val totals = Repo.dailyTotals(c.id, from, today)
            fun dayVal(d: LocalDate) = totals[d.toString()] ?: 0
            if (NotifField.C_YESTERDAY in f) parts += "wczoraj ${Num.fmt(dayVal(today.minusDays(1)))}"
            if (NotifField.C_WEEK in f) {
                var sum = 0
                var d = today.with(DayOfWeek.MONDAY)
                while (!d.isAfter(today)) { sum += dayVal(d); d = d.plusDays(1) }
                parts += "tydzień ${Num.fmt(sum)}"
            }
            if (NotifField.C_AVG7 in f) {
                // 7 pełnych dni przed dzisiejszym
                val avg = (1..7).sumOf { dayVal(today.minusDays(it.toLong())) } / 7.0
                parts += "śr. 7 dni ${Num.fmt(avg)}"
            }
        }
        val text = if (parts.isEmpty()) "Dziś" else "Dziś · " + parts.joinToString(" · ")
        val req = c.id.toInt() * 4
        val b = NotificationCompat.Builder(ctx, CH_PANEL)
            .setSmallIcon(R.drawable.ic_stat_tally)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(Palette.lightArgb(c.color))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSortKey(String.format(java.util.Locale.ROOT, "a%04d", order))
            .setContentIntent(openApp(ctx, 0))
            .addAction(0, "−${Num.fmt(c.step)}", action(ctx, ACTION_DEC, c.id, req + 1))
            .addAction(0, "+${Num.fmt(c.step)}", action(ctx, ACTION_INC, c.id, req + 2))
        if (parts.size > 1) b.setStyle(NotificationCompat.BigTextStyle().bigText(parts.joinToString("\n")))
        if (goal != null && NotifField.C_BAR in f) b.setProgress(goal, total.coerceIn(0, goal), false)
        lockScreen(ctx, b, c.name, hide, R.drawable.ic_stat_tally)
        return b.build()
    }

    /** Ekran blokady: pełna treść albo — gdy wybrano ukrywanie — sama nazwa. */
    private fun lockScreen(ctx: Context, b: NotificationCompat.Builder, name: String, hide: Boolean, icon: Int) {
        if (!hide) {
            b.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            return
        }
        b.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        b.setPublicVersion(
            NotificationCompat.Builder(ctx, CH_PANEL)
                .setSmallIcon(icon)
                .setContentTitle(name)
                .setSilent(true)
                .setShowWhen(false)
                .build()
        )
    }

    private fun trackerNotification(ctx: Context, t: Tracker, s: Session?, order: Int, f: Set<NotifField>, hide: Boolean): Notification {
        val req = 1_000_000 + t.id.toInt() * 4
        val b = NotificationCompat.Builder(ctx, if (s != null) CH_RUNNING else CH_PANEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(Palette.lightArgb(t.color))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setSortKey(String.format(java.util.Locale.ROOT, "b%04d", order))
            .setContentIntent(openApp(ctx, 1))
        if (s != null) {
            val pauses = Repo.pauses(s.id)
            val now = System.currentTimeMillis()
            val active = Stats.activeMs(s, pauses, now)
            val open = pauses.firstOrNull { it.end == null }
            val pausedMs = now - s.start - active
            val parts = ArrayList<String>()
            if (NotifField.R_START in f) parts += "od ${Dates.hm(s.start)}"
            if (NotifField.R_PAUSES in f && pauses.isNotEmpty()) parts += "pauzy: ${pauses.size} (${Dates.dur(pausedMs)})"
            if (open != null) {
                val pausedParts = ArrayList<String>()
                if (NotifField.R_CLOCK in f) pausedParts += "Czas ${Dates.clock(active)}"
                pausedParts += "wstrzymane o ${Dates.hm(open.start)}"
                b.setContentTitle("${t.name} · pauza")
                    .setContentText(pausedParts.joinToString(" · "))
                    .setShowWhen(false)
                    .addAction(0, "Wznów", action(ctx, ACTION_RESUME, t.id, req + 3))
                    .addAction(0, "Stop", action(ctx, ACTION_STOP, t.id, req + 1))
            } else {
                b.setContentTitle("${t.name} · trwa")
                    .setContentText(parts.joinToString(" · "))
                    .addAction(0, "Pauza", action(ctx, ACTION_PAUSE, t.id, req + 0))
                    .addAction(0, "Stop", action(ctx, ACTION_STOP, t.id, req + 1))
                if (NotifField.R_CLOCK in f) {
                    // Stoper liczy od (teraz − czas aktywny), więc pokazuje czas bez pauz.
                    b.setUsesChronometer(true).setShowWhen(true).setWhen(now - active)
                } else {
                    b.setShowWhen(false)
                }
            }
        } else {
            val parts = ArrayList<String>()
            if (NotifField.T_LAST in f) {
                val last = Repo.lastFinished(t.id)
                val lastEnd = last?.end
                if (last != null && lastEnd != null) {
                    parts += "ostatnio ${Dates.dayOf(last.start)}, ${Dates.dur(Stats.activeMs(last, Repo.pauses(last.id), lastEnd))}"
                }
            }
            if (NotifField.T_TODAY in f || NotifField.T_AVG in f) {
                val today = LocalDate.now()
                val fromTs = Dates.startOf(if (NotifField.T_AVG in f) today.minusDays(30) else today)
                val done = Repo.sessions(t.id, fromTs).filter { it.end != null }
                val ps = Repo.pausesFor(done.map { it.id })
                fun dur(x: Session) = Stats.activeMs(x, ps[x.id] ?: emptyList(), x.end!!)
                if (NotifField.T_TODAY in f) {
                    val todays = done.filter { it.start >= Dates.startOf(today) }
                    parts += if (todays.isEmpty()) "dziś jeszcze nie było"
                    else "dziś ${todays.size}× · ${Dates.dur(todays.sumOf { dur(it) })}"
                }
                if (NotifField.T_AVG in f && done.isNotEmpty()) {
                    parts += "śr. ${Dates.dur(done.sumOf { dur(it) } / done.size)}"
                }
            }
            b.setContentTitle(t.name)
                .setContentText(if (parts.isEmpty()) "Naciśnij Start, gdy się zacznie" else parts.joinToString(" · "))
                .setShowWhen(false)
                .addAction(0, "Start", action(ctx, ACTION_START, t.id, req + 2))
            if (parts.size > 1) b.setStyle(NotificationCompat.BigTextStyle().bigText(parts.joinToString("\n")))
        }
        lockScreen(ctx, b, t.name, hide, R.drawable.ic_stat_timer)
        return b.build()
    }

    fun goalReached(ctx: Context, c: Counter, total: Int) {
        if (!Prefs.goalNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_GOAL)
            .setSmallIcon(R.drawable.ic_stat_tally)
            .setColor(Palette.lightArgb(c.color))
            .setContentTitle("Cel osiągnięty: ${c.name}")
            .setContentText("Dziś ${Num.fmt(total)} z ${Num.fmt(c.goal ?: total)}. Dobra robota!")
            .setAutoCancel(true)
            .setTimeoutAfter(6 * 3_600_000L)
            .setContentIntent(openApp(ctx, 0))
            .build()
        post(ctx, BASE_GOAL + c.id.toInt(), n)
    }

    fun reminder(ctx: Context, missing: List<Pair<Counter, Int>>) {
        val lines = missing.map { (c, v) -> "${c.name}: ${Num.fmt(v)} / ${Num.fmt(c.goal ?: 0)}" }
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_tally)
            .setColor(Palette.lightArgb(0))
            .setContentTitle(if (missing.size == 1) "Do celu jeszcze brakuje" else "Cele na dziś nie są jeszcze osiągnięte")
            .setContentText(lines.joinToString(", "))
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 0))
            .build()
        post(ctx, ID_REMINDER, n)
    }

    fun overdue(ctx: Context, s: Session, t: Tracker, activeMs: Long) {
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(Palette.lightArgb(t.color))
            .setContentTitle("${t.name} trwa już ${Dates.dur(activeMs)}")
            .setContentText("Zaczęte ${Dates.dateTime(s.start)}. Czy zapomniałeś zatrzymać?")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 1))
            .addAction(0, "Zatrzymaj teraz", action(ctx, ACTION_STOP, t.id, 2_000_000 + s.id.toInt()))
            .build()
        post(ctx, BASE_OVERDUE + s.id.toInt(), n)
    }

    fun cancelOverdue(ctx: Context, sessionId: Long) = cancel(ctx, BASE_OVERDUE + sessionId.toInt())
}
