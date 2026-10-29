package com.waqtak.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.waqtak.app.MainActivity
import com.waqtak.app.domain.HydrationPlanner
import com.waqtak.app.domain.SessionState
import com.waqtak.app.graph
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Keeps exactly one pending AlarmManager entry per event type (stable request code + action),
 * always pointing at the next event. Updating replaces it, so nothing is ever duplicated.
 *
 *  - meal start / meal end: setAlarmClock (user-visible, exact, survives Doze);
 *  - drink / caffeine: setExactAndAllowWhileIdle, or an inexact window without the permission.
 */
object AlarmScheduler {
    const val ACTION_MEAL_START = "com.waqtak.app.alarm.MEAL_START"
    const val ACTION_MEAL_END = "com.waqtak.app.alarm.MEAL_END"
    const val ACTION_DRINK = "com.waqtak.app.alarm.DRINK"
    const val ACTION_CAFFEINE = "com.waqtak.app.alarm.CAFFEINE"
    const val ACTION_TEST = "com.waqtak.app.alarm.TEST"
    const val EXTRA_AT = "at"

    const val RC_MEAL_START = 1001
    const val RC_MEAL_END = 1002
    const val RC_DRINK = 1003
    const val RC_CAFFEINE = 1004
    const val RC_TEST = 1005

    private const val TAG = "WaqtakScheduler"
    private val mutex = Mutex()

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun intent(ctx: Context, action: String) = Intent(ctx, AlarmReceiver::class.java).setAction(action)

    fun pendingIntent(ctx: Context, action: String, rc: Int, at: Long? = null, noCreate: Boolean = false): PendingIntent? {
        val i = intent(ctx, action).apply { at?.let { putExtra(EXTRA_AT, it) } }
        val flags = PendingIntent.FLAG_IMMUTABLE or if (noCreate) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(ctx, rc, i, flags)
    }

    fun isScheduled(ctx: Context, action: String, rc: Int): Boolean = pendingIntent(ctx, action, rc, noCreate = true) != null

    private fun setCritical(ctx: Context, action: String, rc: Int, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val op = pendingIntent(ctx, action, rc, at)!!
        val show = PendingIntent.getActivity(
            ctx, rc, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            if (canExact(ctx)) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), op)
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, falling back", e)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
    }

    private fun setSoft(ctx: Context, action: String, rc: Int, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val op = pendingIntent(ctx, action, rc, at)!!
        try {
            if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused, falling back", e)
        }
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, op)
    }

    fun cancel(ctx: Context, action: String, rc: Int) {
        pendingIntent(ctx, action, rc, noCreate = true)?.let {
            ctx.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    fun cancelAll(ctx: Context) {
        cancel(ctx, ACTION_MEAL_START, RC_MEAL_START)
        cancel(ctx, ACTION_MEAL_END, RC_MEAL_END)
        cancel(ctx, ACTION_DRINK, RC_DRINK)
        cancel(ctx, ACTION_CAFFEINE, RC_CAFFEINE)
    }

    /** Test alarm for the readiness screen: same path as a real meal alarm. */
    fun scheduleTest(ctx: Context, delayMs: Long = 15_000): Long {
        val at = System.currentTimeMillis() + delayMs
        setCritical(ctx, ACTION_TEST, RC_TEST, at)
        return at
    }

    fun cancelTest(ctx: Context) = cancel(ctx, ACTION_TEST, RC_TEST)

    data class Plan(val mealStart: Long?, val mealEnd: Long?, val drink: Long?, val caffeine: Long?)

    suspend fun rescheduleAll(ctx: Context): Plan = mutex.withLock {
        val g = ctx.graph
        val repo = g.repo
        val now = repo.clock.now()
        val s = g.settings.get()
        if (!s.onboardingDone) {
            cancelAll(ctx)
            return@withLock Plan(null, null, null, null)
        }

        val nextMeal = repo.nextMealStart(now)?.atMillis?.let { maxOf(it, now + 1_000) }
        if (nextMeal != null) setCritical(ctx, ACTION_MEAL_START, RC_MEAL_START, nextMeal) else cancel(ctx, ACTION_MEAL_START, RC_MEAL_START)

        val active = repo.openSessions().filter { it.state == SessionState.ACTIVE.name }
        val end = active.minOfOrNull { repo.sessionEndWall(it) }?.let { maxOf(it, now + 1_000) }
        if (end != null) setCritical(ctx, ACTION_MEAL_END, RC_MEAL_END, end) else cancel(ctx, ACTION_MEAL_END, RC_MEAL_END)

        val snap = repo.schedule()
        val from = now - 86_400_000L
        val to = now + 2 * 86_400_000L
        val windows = repo.wakingWindows(snap, from, to)

        val drink = if (s.hydrationEnabled && !snap.isEmpty) {
            val mealStarts = repo.occurrences(snap, from, to).map { it.atMillis }
            HydrationPlanner.next(now, windows, s.hydrationIntervalMin, mealStarts, s.drinkHandledUpTo, s.drinkSnoozeUntil)
        } else null
        if (drink != null) setSoft(ctx, ACTION_DRINK, RC_DRINK, maxOf(drink, now + 1_000)) else cancel(ctx, ACTION_DRINK, RC_DRINK)

        val caffeine = if (s.caffeineReminderEnabled) {
            windows.map { it.dayDate to it.sleepAt - s.caffeineCutoffHours * 3_600_000L }
                .firstOrNull { (day, t) ->
                    t > now && day.toEpochDay() != s.caffeineNotifiedEpochDay &&
                        windows.first { w -> w.dayDate == day }.wakeAt < t
                }?.second
        } else null
        if (caffeine != null) setSoft(ctx, ACTION_CAFFEINE, RC_CAFFEINE, caffeine) else cancel(ctx, ACTION_CAFFEINE, RC_CAFFEINE)

        Log.i(TAG, "next meal=${nextMeal?.let(Instant::ofEpochMilli)} end=${end?.let(Instant::ofEpochMilli)} drink=${drink?.let(Instant::ofEpochMilli)}")
        Plan(nextMeal, end, drink, caffeine)
    }
}
