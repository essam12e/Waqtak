package com.waqtak.app.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.waqtak.app.domain.Alert
import com.waqtak.app.domain.AlertKind
import com.waqtak.app.domain.DrinkReminderStyle
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.HydrationPlanner
import com.waqtak.app.domain.SessionState
import com.waqtak.app.graph
import kotlinx.coroutines.launch
import java.time.Instant

private fun BroadcastReceiver.runAsync(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.graph.scope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e("WaqtakReceiver", "receiver failed", e)
        } finally {
            pending.finish()
        }
    }
}

/** Fired by AlarmManager. Not exported. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val at = intent.getLongExtra(AlarmScheduler.EXTRA_AT, System.currentTimeMillis())
        runAsync(ctx) {
            when (intent.action) {
                AlarmScheduler.ACTION_MEAL_START -> AlarmEvents.onMealStart(ctx)
                AlarmScheduler.ACTION_MEAL_END -> AlarmEvents.onMealEnd(ctx)
                AlarmScheduler.ACTION_DRINK -> AlarmEvents.onDrink(ctx, at)
                AlarmScheduler.ACTION_CAFFEINE -> AlarmEvents.onCaffeine(ctx, at)
                AlarmScheduler.ACTION_TEST -> AlarmEvents.onTest(ctx, at)
            }
        }
    }
}

/** Boot, app update, clock/zone changes and exact-alarm permission changes: rebuild the alarm plan. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val reason = intent.action ?: return
        when (reason) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> runAsync(ctx) {
                if (reason == Intent.ACTION_LOCALE_CHANGED) Notifications.ensureChannels(ctx)
                Reconciler.reconcile(ctx, reason)
            }
        }
    }
}

/** Buttons on notifications. Not exported. */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START = "com.waqtak.app.action.START"
        const val ACTION_SNOOZE = "com.waqtak.app.action.SNOOZE"
        const val ACTION_SKIP = "com.waqtak.app.action.SKIP"
        const val ACTION_END_OK = "com.waqtak.app.action.END_OK"
        const val ACTION_END_EXTEND = "com.waqtak.app.action.END_EXTEND"
        const val ACTION_TEST_OK = "com.waqtak.app.action.TEST_OK"
        const val ACTION_DRANK = "com.waqtak.app.action.DRANK"
        const val ACTION_DRINK_SNOOZE = "com.waqtak.app.action.DRINK_SNOOZE"
        const val ACTION_DRINK_SKIP = "com.waqtak.app.action.DRINK_SKIP"
        const val ACTION_FINISH_MEAL = "com.waqtak.app.action.FINISH_MEAL"
        const val ACTION_ALARM_DRANK = "com.waqtak.app.action.ALARM_DRANK"
        const val ACTION_ALARM_DRINK_CLOSE = "com.waqtak.app.action.ALARM_DRINK_CLOSE"
        const val ACTION_ALARM_DRINK_SNOOZE = "com.waqtak.app.action.ALARM_DRINK_SNOOZE"
        const val EXTRA_ALERT = "alert"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_SESSION = "session"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val alertId = intent.getStringExtra(EXTRA_ALERT)
        // stop sound synchronously, before any I/O
        if (alertId != null) AlarmController.silenceNow(alertId)
        runAsync(ctx) {
            val userAction = when (intent.action) {
                ACTION_START -> UserAction.START
                ACTION_SNOOZE -> UserAction.SNOOZE
                ACTION_SKIP -> UserAction.SKIP
                ACTION_END_OK -> UserAction.END_OK
                ACTION_END_EXTEND -> UserAction.END_EXTEND
                ACTION_TEST_OK -> UserAction.TEST_OK
                ACTION_ALARM_DRANK -> UserAction.DRINK_LOG
                ACTION_ALARM_DRINK_CLOSE -> UserAction.DRINK_CLOSE
                ACTION_ALARM_DRINK_SNOOZE -> UserAction.DRINK_SNOOZE
                else -> null
            }
            if (userAction != null && alertId != null) {
                AlarmController.perform(ctx, alertId, userAction)
                return@runAsync
            }
            val repo = ctx.graph.repo
            when (intent.action) {
                ACTION_DRANK -> {
                    val token = intent.getStringExtra(EXTRA_TOKEN) ?: return@runAsync
                    val amount = intent.getIntExtra(EXTRA_AMOUNT, 250)
                    repo.logDrink(DrinkType.WATER, amount, hasCalories = false, source = "REMINDER", token = token)
                    Notifications.cancelDrink(ctx)
                }
                ACTION_DRINK_SNOOZE -> {
                    val now = repo.clock.now()
                    ctx.graph.settings.update { it.copy(drinkSnoozeUntil = now + HydrationPlanner.SNOOZE_MS) }
                    Notifications.cancelDrink(ctx)
                    AlarmScheduler.rescheduleAll(ctx)
                }
                ACTION_DRINK_SKIP -> Notifications.cancelDrink(ctx)
                ACTION_FINISH_MEAL -> {
                    val id = intent.getLongExtra(EXTRA_SESSION, -1)
                    if (id > 0) repo.finishSession(id, com.waqtak.app.domain.EndReason.USER_FINISHED)
                    Notifications.cancelTimer(ctx)
                    AlarmScheduler.rescheduleAll(ctx)
                }
            }
        }
    }
}

object AlarmEvents {
    suspend fun onMealStart(ctx: Context) {
        val repo = ctx.graph.repo
        repo.claimDueMeals(repo.clock.now()).forEach { AlarmService.ring(ctx, it) }
        AlarmScheduler.rescheduleAll(ctx)
    }

    suspend fun onMealEnd(ctx: Context) {
        val repo = ctx.graph.repo
        val endAlarm = ctx.graph.settings.get().endAlarmEnabled
        repo.claimDueSessionEnds(repo.clock.now()).forEach { a ->
            if (endAlarm) {
                AlarmService.ring(ctx, a)
            } else {
                // the user turned the end alarm off: a quiet note instead of a ringing alarm
                a.ref.toLongOrNull()?.let { repo.finishSession(it, com.waqtak.app.domain.EndReason.TIMER_ACK) }
                Notifications.showMealEndNote(ctx)
            }
        }
        TimerSync.sync(ctx)
        AlarmScheduler.rescheduleAll(ctx)
    }

    suspend fun onDrink(ctx: Context, scheduledAt: Long) {
        val g = ctx.graph
        val repo = g.repo
        val now = repo.clock.now()
        g.settings.update {
            it.copy(
                drinkHandledUpTo = maxOf(it.drinkHandledUpTo, minOf(scheduledAt, now)),
                drinkSnoozeUntil = if (it.drinkSnoozeUntil <= now + 60_000) 0 else it.drinkSnoozeUntil,
            )
        }
        val s = g.settings.get()
        val activeSession = repo.openSessions().firstOrNull()
        val ringing = AlarmService.instance?.activeAlertIds()?.isNotEmpty() == true
        when {
            !s.hydrationEnabled -> Unit
            // Meal alerts have priority: merge the drink reminder by postponing it.
            activeSession != null -> {
                val until = if (activeSession.state == SessionState.ACTIVE.name) repo.sessionEndWall(activeSession) else now
                g.settings.update { it.copy(drinkSnoozeUntil = maxOf(until, now) + 10 * 60_000L) }
            }
            ringing -> g.settings.update { it.copy(drinkSnoozeUntil = now + HydrationPlanner.SNOOZE_MS) }
            HydrationPlanner.recentlyDrank(now, repo.lastDrinkAt(), s.hydrationIntervalMin) -> Unit
            s.hasFluidRestriction && s.fluidGoalMl > 0 && todayTotal(ctx) >= s.fluidGoalMl -> Unit
            s.drinkStyle == DrinkReminderStyle.ALARM ->
                AlarmService.ring(
                    ctx, Alert("drink:$scheduledAt", AlertKind.DRINK, null, "", scheduledAt, scheduledAt.toString(), durationMin = s.defaultDrinkMl),
                )
            else -> Notifications.showDrinkReminder(ctx, token = "reminder:$scheduledAt", amountMl = s.defaultDrinkMl)
        }
        AlarmScheduler.rescheduleAll(ctx)
    }

    private suspend fun todayTotal(ctx: Context): Int {
        val repo = ctx.graph.repo
        val zone = repo.clock.zone()
        val start = Instant.ofEpochMilli(repo.clock.now()).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return repo.drinks(start, start + 86_400_000L).sumOf { it.amountMl }
    }

    suspend fun onCaffeine(ctx: Context, at: Long) {
        val g = ctx.graph
        val zone = g.repo.clock.zone()
        val s = g.settings.get()
        // the reminder belongs to the waking day whose sleep it precedes; remember it to show it once per day
        val windows = g.repo.wakingWindows(g.repo.schedule(), at - 86_400_000L, at + 86_400_000L)
        val day = windows.firstOrNull { at in it.wakeAt..it.sleepAt }?.dayDate
            ?: Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        if (s.caffeineReminderEnabled && day.toEpochDay() != s.caffeineNotifiedEpochDay) {
            Notifications.showCaffeineNote(ctx)
        }
        g.settings.update { it.copy(caffeineNotifiedEpochDay = day.toEpochDay()) }
        AlarmScheduler.rescheduleAll(ctx)
    }

    suspend fun onTest(ctx: Context, at: Long) {
        AlarmService.ring(ctx, Alert("test:$at", AlertKind.TEST, null, "", at, at.toString()))
    }
}
