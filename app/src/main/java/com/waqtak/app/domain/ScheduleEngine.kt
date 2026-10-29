package com.waqtak.app.domain

/**
 * Local, deterministic meal-time suggestions. All numbers here are organisational
 * defaults chosen for the app, not medical limits:
 *  - first meal about 60 min after waking;
 *  - following meals about 4–5 h apart when the day is long enough (never closer than 3 h
 *    when the app suggests times; the user can still choose anything);
 *  - the last meal ends about 2 h before sleep, or about 3 h with night-time reflux.
 *
 * Times are minutes of the day (0..1439); a day may cross midnight (sleep < wake).
 */
object ScheduleEngine {
    const val FIRST_MEAL_OFFSET = 60
    const val MIN_SPACING = 180
    const val COMFORT_SPACING = 240
    const val MAX_SPACING = 300
    const val GAP_BEFORE_SLEEP = 120
    const val REFLUX_GAP_BEFORE_SLEEP = 180
    const val MIN_AWAKE = 300
    private const val ROUND = 15

    data class SuggestedMeal(val type: MealType, val minuteOfDay: Int, val durationMin: Int)

    enum class Note {
        /** Wake/sleep produce a waking day too short to plan; ask the user to check the times. */
        INVALID_TIMES,
        /** Not all requested meals fit without squeezing them; fewer meals are proposed. */
        SHORT_DAY_REDUCED,
        /** Meals are less than 4 h apart because the day is short. */
        SHORT_SPACING,
        /** The longer gap before sleep for reflux was applied. */
        REFLUX_GAP_APPLIED,
        /** There is a long stretch after the last meal; an optional snack could fit. */
        ROOM_FOR_SNACK,
        /** Day crosses midnight; times after midnight belong to the same waking day. */
        CROSSES_MIDNIGHT,
    }

    data class Proposal(
        val meals: List<SuggestedMeal>,
        val notes: Set<Note>,
        val requested: List<MealType>,
        val dropped: List<MealType>,
    )

    fun awakeMinutes(wake: Int, sleep: Int): Int = ((sleep - wake) % 1440 + 1440) % 1440

    /** Minutes since waking for a time of day, handling days that cross midnight. */
    fun relative(wake: Int, minute: Int): Int = ((minute - wake) % 1440 + 1440) % 1440

    private val dropOrder = listOf(MealType.SNACK, MealType.LUNCH, MealType.DINNER, MealType.BREAKFAST)

    fun propose(
        wake: Int,
        sleep: Int,
        requested: List<MealType>,
        reflux: Boolean,
        durations: Map<MealType, Int> = emptyMap(),
    ): Proposal {
        val wanted = requested.distinct().sortedBy { it.order }
        val notes = mutableSetOf<Note>()
        val awake = awakeMinutes(wake, sleep)
        if (wanted.isEmpty()) return Proposal(emptyList(), notes, wanted, emptyList())
        if (awake < MIN_AWAKE) {
            return Proposal(emptyList(), setOf(Note.INVALID_TIMES), wanted, wanted)
        }
        if (sleep < wake) notes += Note.CROSSES_MIDNIGHT
        val gap = if (reflux) REFLUX_GAP_BEFORE_SLEEP else GAP_BEFORE_SLEEP
        if (reflux) notes += Note.REFLUX_GAP_APPLIED
        fun dur(t: MealType) = durations[t] ?: MealDefaults.durationFor(t)

        var current = wanted
        while (current.isNotEmpty()) {
            val last = current.last()
            val latestLastStart = awake - gap - dur(last)
            val first = FIRST_MEAL_OFFSET
            val starts: List<Int>? = if (current.size == 1) {
                if (latestLastStart >= 0) listOf(minOf(first, latestLastStart)) else null
            } else {
                val span = latestLastStart - first
                val raw = span / (current.size - 1)
                if (raw < MIN_SPACING) null else {
                    val spacing = (minOf(raw, MAX_SPACING) / ROUND) * ROUND
                    if (spacing < COMFORT_SPACING) notes += Note.SHORT_SPACING
                    current.indices.map { first + it * spacing }
                }
            }
            if (starts != null) {
                val dropped = wanted - current.toSet()
                if (dropped.isNotEmpty()) notes += Note.SHORT_DAY_REDUCED
                val lastEnd = starts.last() + dur(last)
                if (MealType.SNACK !in current && awake - gap - lastEnd >= 6 * 60) notes += Note.ROOM_FOR_SNACK
                val meals = current.zip(starts).map { (t, r) -> SuggestedMeal(t, (wake + r) % 1440, dur(t)) }
                return Proposal(meals, notes, wanted, dropped)
            }
            val toDrop = dropOrder.first { it in current }
            current = current - toDrop
        }
        return Proposal(emptyList(), notes + Note.INVALID_TIMES, wanted, wanted)
    }

    enum class Issue { OUTSIDE_WAKING_HOURS, CLOSE_TO_SLEEP, VERY_CLOSE_MEALS }

    data class MealSlot(val id: Long, val minuteOfDay: Int, val durationMin: Int)

    /** Gentle review of a user-edited schedule. Never blocks saving; only informs. */
    fun review(wake: Int, sleep: Int, meals: List<MealSlot>, reflux: Boolean): Map<Long, Set<Issue>> {
        val awake = awakeMinutes(wake, sleep)
        val gap = if (reflux) REFLUX_GAP_BEFORE_SLEEP else GAP_BEFORE_SLEEP
        val sorted = meals.sortedBy { relative(wake, it.minuteOfDay) }
        val out = mutableMapOf<Long, MutableSet<Issue>>()
        sorted.forEachIndexed { i, m ->
            val r = relative(wake, m.minuteOfDay)
            val issues = mutableSetOf<Issue>()
            if (r > awake) issues += Issue.OUTSIDE_WAKING_HOURS
            else if (r + m.durationMin > awake - gap) issues += Issue.CLOSE_TO_SLEEP
            if (i > 0) {
                val prev = sorted[i - 1]
                if (r - relative(wake, prev.minuteOfDay) < 90) issues += Issue.VERY_CLOSE_MEALS
            }
            if (issues.isNotEmpty()) out[m.id] = issues
        }
        return out
    }
}
