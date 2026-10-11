package pl.kreska.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime

/** Alarmy bez specjalnych uprawnień (mogą się spóźnić o kilka minut w trybie oszczędzania). */
object Alarms {
    const val MIDNIGHT = "pl.kreska.app.MIDNIGHT"
    const val REMINDER = "pl.kreska.app.REMINDER"
    const val OVERDUE = "pl.kreska.app.OVERDUE"

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    private fun pi(ctx: Context, action: String, req: Int, id: Long = -1L, create: Boolean = true): PendingIntent? {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(ctx, req, intent, flags)
    }

    private fun at(ctx: Context, time: Long, p: PendingIntent) {
        am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, p)
    }

    fun scheduleAll(ctx: Context) {
        scheduleMidnight(ctx)
        scheduleReminder(ctx)
        Repo.running().forEach { scheduleOverdue(ctx, it.id, it.start) }
    }

    /** Po północy powiadomienia liczników wracają do zera. */
    fun scheduleMidnight(ctx: Context) {
        val p = pi(ctx, MIDNIGHT, 1) ?: return
        at(ctx, Dates.startOf(LocalDate.now().plusDays(1)) + 30_000L, p)
    }

    fun scheduleReminder(ctx: Context) {
        val p = pi(ctx, REMINDER, 2) ?: return
        if (!Prefs.reminderOn(ctx)) {
            am(ctx).cancel(p)
            return
        }
        val m = Prefs.reminderMinutes(ctx)
        var dt = LocalDate.now().atTime(m / 60, m % 60)
        if (!dt.isAfter(LocalDateTime.now())) dt = dt.plusDays(1)
        at(ctx, dt.atZone(Dates.zone).toInstant().toEpochMilli(), p)
    }

    fun scheduleOverdue(ctx: Context, sessionId: Long, start: Long) {
        val p = pi(ctx, OVERDUE, 10_000 + sessionId.toInt(), sessionId) ?: return
        val h = Prefs.overdueHours(ctx)
        if (h <= 0) {
            am(ctx).cancel(p)
            return
        }
        val t = start + h * 3_600_000L
        if (t <= System.currentTimeMillis()) return
        at(ctx, t, p)
    }

    fun cancelOverdue(ctx: Context, sessionId: Long) {
        pi(ctx, OVERDUE, 10_000 + sessionId.toInt(), sessionId, create = false)?.let { am(ctx).cancel(it) }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)
        when (intent.action) {
            Alarms.MIDNIGHT -> {
                Notifier.refresh(ctx)
                Repo.bump()
                Alarms.scheduleMidnight(ctx)
            }
            Alarms.REMINDER -> {
                val missing = Repo.counters().mapNotNull { c ->
                    val goal = c.goal ?: return@mapNotNull null
                    val v = Repo.todayTotal(c.id)
                    if (v < goal) c to v else null
                }
                if (missing.isNotEmpty()) Notifier.reminder(ctx, missing)
                Alarms.scheduleReminder(ctx)
            }
            Alarms.OVERDUE -> {
                val s = Repo.session(intent.getLongExtra(EXTRA_ID, -1L)) ?: return
                if (s.end != null) return
                val t = Repo.tracker(s.trackerId) ?: return
                Notifier.overdue(ctx, s, t)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)
        Notifier.createChannels(ctx)
        Alarms.scheduleAll(ctx)
        Notifier.refresh(ctx)
    }
}

/** Obsługa przycisków z powiadomień — działa bez otwierania aplikacji. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        when (intent.action) {
            ACTION_INC -> Repo.increment(id)
            ACTION_DEC -> Repo.decrement(id)
            ACTION_START -> Repo.startSession(id)
            ACTION_STOP -> Repo.stopTracker(id)
        }
    }
}
