package com.waqtak.app.domain

/**
 * Local, deterministic meal-time suggestions. All numbers here are organisational
 * defaults chosen for the app, not medical limits:
 *  - first meal about 60 min after waking;
 *  - following meals about 4–5 h apart when the day is long enough;
 *  - on a short day no meal is removed: meals move closer and the user is told they may
 *    untick a meal they do not need;
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
    /** Closest spacing used on a short day (meals are never squeezed below this). */
    const val MIN_SHORT_SPACING = 90
    private const val ROUND = 15

    data class SuggestedMeal(val type: MealType, val minuteOfDay: Int, val durationMin: Int)

    enum class Note {
        /** Wake/sleep produce a waking day too short to plan; ask the user to check the times. */
        INVALID_TIMES,
        /** Short day: meals are under 3 h apart; the user may choose fewer meals. Nothing is removed. */
        SHORT_DAY,
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

        val last = wanted.last()
        val latestLastStart = awake - gap - dur(last)
        val first = minOf(FIRST_MEAL_OFFSET, maxOf(0, latestLastStart))
        val starts: List<Int> = if (wanted.size == 1) {
            listOf(first)
        } else {
            val raw = (latestLastStart - first) / (wanted.size - 1)
            val spacing = (minOf(maxOf(raw, MIN_SHORT_SPACING), MAX_SPACING) / ROUND) * ROUND
            if (raw < MIN_SPACING) notes += Note.SHORT_DAY
            else if (spacing < COMFORT_SPACING) notes += Note.SHORT_SPACING
            wanted.indices.map { first + it * spacing }
        }
        val lastEnd = starts.last() + dur(last)
        if (MealType.SNACK !in wanted && awake - gap - lastEnd >= 6 * 60) notes += Note.ROOM_FOR_SNACK
        val meals = wanted.zip(starts).map { (t, r) -> SuggestedMeal(t, (wake + r) % 1440, dur(t)) }
        return Proposal(meals, notes, wanted, emptyList())
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
