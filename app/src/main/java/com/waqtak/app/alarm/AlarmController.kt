package com.waqtak.app.alarm

import android.content.Context
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.EndReason
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.domain.MealType
import com.waqtak.app.graph
import com.waqtak.app.util.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class UserAction { START, SNOOZE, SKIP, END_OK, END_EXTEND, TEST_OK, DRINK_LOG, DRINK_CLOSE, DRINK_SNOOZE }

/**
 * Handles a user decision on a ringing alert. The sound is stopped first, synchronously,
 * then the state change is recorded. Every state change is conditional in the database,
 * so a double tap (screen + notification, or two fast taps) has the effect of one.
 */
object AlarmController {
    fun silenceNow(alertId: String) {
        AlarmService.instance?.silence(alertId)
    }

    suspend fun perform(
        context: Context,
        alertId: String,
        action: UserAction,
        durationMin: Int? = null,
        fullness: Int? = null,
        drinkType: DrinkType = DrinkType.WATER,
        amountMl: Int? = null,
    ) {
        val ctx = context.applicationContext
        withContext(Dispatchers.Main.immediate) {
            silenceNow(alertId)
            RingingHub.markResolved(alertId)
        }
        Notifications.cancelFallback(ctx, alertId)
        val repo = ctx.graph.repo
        val ref = alertId.substringAfter(':')
        when (action) {
            UserAction.START -> repo.startMeal(ref, durationMin)?.let { id -> TimerSync.show(ctx, id) }
            UserAction.SNOOZE -> repo.snoozeMeal(ref)
            UserAction.SKIP -> repo.skipMeal(ref)
            UserAction.END_OK -> {
                ref.toLongOrNull()?.let { repo.finishSession(it, EndReason.TIMER_ACK, fullness) }
                Notifications.cancelTimer(ctx)
            }
            UserAction.END_EXTEND -> ref.toLongOrNull()?.let { id ->
                repo.extendSession(id)
                TimerSync.show(ctx, id)
            }
            UserAction.TEST_OK -> Unit
            // Logging happens only on the explicit "I drank ..." button, never on silencing.
            UserAction.DRINK_LOG -> {
                val s = ctx.graph.settings.get()
                repo.logDrink(
                    drinkType, amountMl ?: s.defaultDrinkMl, hasCalories = drinkType.defaultHasCalories,
                    source = "REMINDER", token = "reminder:$ref",
                )
            }
            UserAction.DRINK_CLOSE -> Unit
            UserAction.DRINK_SNOOZE -> {
                val until = repo.clock.now() + MealDefaults.DRINK_SNOOZE_MINUTES * 60_000L
                ctx.graph.settings.update { it.copy(drinkSnoozeUntil = until) }
            }
        }
        if (action == UserAction.DRINK_LOG || action == UserAction.DRINK_CLOSE || action == UserAction.DRINK_SNOOZE) {
            Notifications.cancelDrink(ctx)
        }
        withContext(Dispatchers.Main.immediate) { AlarmService.instance?.resolve(alertId) }
        AlarmScheduler.rescheduleAll(ctx)
    }
}

/** Keeps the silent ongoing countdown notification in line with the current meal session. */
object TimerSync {
    suspend fun show(context: Context, sessionId: Long) {
        val repo = context.graph.repo
        val s = repo.session(sessionId) ?: return
        val ctx = AppLocale.wrap(context)
        val title = s.label.ifBlank { Notifications.mealName(ctx, MealType.parse(s.mealType)) }
        Notifications.showTimer(context, s.id, title, repo.sessionEndWall(s))
    }

    suspend fun sync(context: Context) {
        val open = context.graph.repo.openSessions().firstOrNull { it.state == "ACTIVE" }
        if (open == null) Notifications.cancelTimer(context) else show(context, open.id)
    }
}
