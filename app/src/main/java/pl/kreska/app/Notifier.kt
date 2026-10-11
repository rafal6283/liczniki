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

const val EXTRA_ID = "pl.kreska.app.ID"
const val ACTION_INC = "pl.kreska.app.INC"
const val ACTION_DEC = "pl.kreska.app.DEC"
const val ACTION_START = "pl.kreska.app.START"
const val ACTION_STOP = "pl.kreska.app.STOP"
const val ACTION_PAUSE = "pl.kreska.app.PAUSE"
const val ACTION_RESUME = "pl.kreska.app.RESUME"

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
        Repo.counters().forEachIndexed { i, c ->
            if (c.notify) {
                val id = BASE_COUNTER + c.id.toInt()
                wanted += id
                post(ctx, id, counterNotification(ctx, c, i))
            }
        }
        val running = Repo.running().associateBy { it.trackerId }
        Repo.trackers().forEachIndexed { i, t ->
            val s = running[t.id]
            if (s != null || t.notify) {
                val id = BASE_TRACKER + t.id.toInt()
                wanted += id
                post(ctx, id, trackerNotification(ctx, t, s, i))
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

    private fun counterNotification(ctx: Context, c: Counter, order: Int): Notification {
        val total = Repo.todayTotal(c.id)
        val last = Repo.lastTap(c.id)
        val goal = c.goal
        val title = if (goal != null) "${c.name}: ${Num.fmt(total)} / ${Num.fmt(goal)}" else "${c.name}: ${Num.fmt(total)}"
        val text = if (last != null) "Dziś · ostatnio ${Dates.hm(last)}" else "Dziś jeszcze nic"
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSortKey(String.format(java.util.Locale.ROOT, "a%04d", order))
            .setContentIntent(openApp(ctx, 0))
            .addAction(0, "−${Num.fmt(c.step)}", action(ctx, ACTION_DEC, c.id, req + 1))
            .addAction(0, "+${Num.fmt(c.step)}", action(ctx, ACTION_INC, c.id, req + 2))
        if (goal != null) b.setProgress(goal, total.coerceIn(0, goal), false)
        return b.build()
    }

    private fun trackerNotification(ctx: Context, t: Tracker, s: Session?, order: Int): Notification {
        val req = 1_000_000 + t.id.toInt() * 4
        val b = NotificationCompat.Builder(ctx, if (s != null) CH_RUNNING else CH_PANEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(Palette.lightArgb(t.color))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSortKey(String.format(java.util.Locale.ROOT, "b%04d", order))
            .setContentIntent(openApp(ctx, 1))
        if (s != null) {
            val pauses = Repo.pauses(s.id)
            val now = System.currentTimeMillis()
            val active = Stats.activeMs(s, pauses, now)
            val open = pauses.firstOrNull { it.end == null }
            val pausedMs = now - s.start - active
            val pauseInfo = if (pauses.isNotEmpty()) " · pauzy: ${pauses.size} (${Dates.dur(pausedMs)})" else ""
            if (open != null) {
                b.setContentTitle("${t.name} · pauza")
                    .setContentText("Czas ${Dates.clock(active)} · wstrzymane o ${Dates.hm(open.start)}")
                    .setShowWhen(false)
                    .addAction(0, "Wznów", action(ctx, ACTION_RESUME, t.id, req + 3))
                    .addAction(0, "Stop", action(ctx, ACTION_STOP, t.id, req + 1))
            } else {
                // Stoper liczy od (teraz − czas aktywny), więc pokazuje czas bez pauz.
                b.setContentTitle("${t.name} · trwa")
                    .setContentText("od ${Dates.hm(s.start)}$pauseInfo")
                    .setUsesChronometer(true)
                    .setShowWhen(true)
                    .setWhen(now - active)
                    .addAction(0, "Pauza", action(ctx, ACTION_PAUSE, t.id, req + 0))
                    .addAction(0, "Stop", action(ctx, ACTION_STOP, t.id, req + 1))
            }
        } else {
            val last = Repo.lastFinished(t.id)
            val lastEnd = last?.end
            val text = if (last != null && lastEnd != null) {
                "Ostatnio ${Dates.dayOf(last.start)}, ${Dates.dur(lastEnd - last.start)}"
            } else "Naciśnij Start, gdy się zacznie"
            b.setContentTitle(t.name)
                .setContentText(text)
                .setShowWhen(false)
                .addAction(0, "Start", action(ctx, ACTION_START, t.id, req + 2))
        }
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
