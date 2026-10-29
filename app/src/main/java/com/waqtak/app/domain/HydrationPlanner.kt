package com.waqtak.app.domain

/**
 * Drink reminders spread across waking hours. The interval (default 90 min) is an
 * adjustable organisational choice, not a medical requirement. Reminders are merged
 * into a nearby meal (the meal screen already invites a drink) instead of stacking.
 */
object HydrationPlanner {
    const val MERGE_BEFORE_MEAL_MS = 20 * 60_000L
    const val MERGE_AFTER_MEAL_MS = 10 * 60_000L
    const val QUIET_BEFORE_SLEEP_MS = 60 * 60_000L
    const val SNOOZE_MS = 15 * 60_000L

    fun reminderTimes(window: WakingWindow, intervalMin: Int, mealStarts: List<Long>): List<Long> {
        if (intervalMin < 15) return emptyList()
        val step = intervalMin * 60_000L
        val out = mutableListOf<Long>()
        var t = window.wakeAt + step
        val last = window.sleepAt - QUIET_BEFORE_SLEEP_MS
        while (t <= last) {
            val nearMeal = mealStarts.any { m -> t >= m - MERGE_BEFORE_MEAL_MS && t <= m + MERGE_AFTER_MEAL_MS }
            if (!nearMeal) out += t
            t += step
        }
        return out
    }

    /**
     * Next reminder after [now]; [handledUpTo] is the last reminder time already shown/handled,
     * so a late wake-up never replays a backlog of reminders.
     */
    fun next(
        now: Long,
        windows: List<WakingWindow>,
        intervalMin: Int,
        mealStarts: List<Long>,
        handledUpTo: Long,
        snoozeUntil: Long,
    ): Long? {
        val floor = maxOf(now, handledUpTo)
        val planned = windows.asSequence()
            .flatMap { reminderTimes(it, intervalMin, mealStarts).asSequence() }
            .filter { it > floor }
            .minOrNull()
        val snooze = snoozeUntil.takeIf { it > handledUpTo && it > now - 60_000 }
        return listOfNotNull(planned, snooze).minOrNull()
    }

    /** "Lighten" rule: skip a reminder if the user logged a drink recently. */
    fun recentlyDrank(now: Long, lastDrinkAt: Long?, intervalMin: Int): Boolean =
        lastDrinkAt != null && now - lastDrinkAt < intervalMin * 60_000L / 2
}
