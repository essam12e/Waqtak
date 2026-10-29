package com.waqtak.app.domain

enum class MealType {
    BREAKFAST, LUNCH, SNACK, DINNER;

    /** Canonical order used when distributing meals across the waking day. */
    val order: Int get() = ordinal

    companion object {
        fun parse(value: String?): MealType = entries.firstOrNull { it.name == value } ?: LUNCH
    }
}

enum class ScheduleMode {
    REGULAR, SHIFTS, CUSTOM;

    companion object {
        fun parse(value: String?): ScheduleMode = entries.firstOrNull { it.name == value } ?: REGULAR
    }
}

enum class ProfileKind { WORK, OFF, SHIFT, CUSTOM }

enum class OccurrenceStatus { RINGING, SNOOZED, STARTED, SKIPPED, MISSED }

enum class SessionState { ACTIVE, END_RINGING, FINISHED }

enum class EndReason { USER_FINISHED, TIMER_ACK, EXPIRED_UNATTENDED }

/**
 * Drink categories. [defaultHasCalories] and [mayContainCaffeine] are only defaults
 * for the log form; the user can always change them (e.g. tea with sugar, diet soda).
 */
enum class DrinkType(val defaultHasCalories: Boolean, val mayContainCaffeine: Boolean) {
    WATER(false, false),
    TEA(false, true),
    COFFEE(false, true),
    MILK(true, false),
    LABAN(true, false),
    JUICE(true, false),
    SODA(true, true),
    ENERGY(true, true),
    CUSTOM(false, false);

    companion object {
        fun parse(value: String?): DrinkType = entries.firstOrNull { it.name == value } ?: CUSTOM
    }
}

/** Default meal durations are product settings, not medical limits. */
object MealDefaults {
    fun durationFor(type: MealType): Int = when (type) {
        MealType.BREAKFAST -> 20
        MealType.LUNCH -> 25
        MealType.DINNER -> 20
        MealType.SNACK -> 15
    }

    const val SNOOZE_MINUTES = 10
    const val EXTEND_MINUTES = 5
    const val MIN_DURATION = 5
    const val MAX_DURATION = 90

    /** A meal alarm that fires later than this (device off, doze) is reported as missed instead of ringing. */
    const val START_GRACE_MS = 30 * 60_000L

    /** After a reboot, a meal timer that ended within this window rings exactly once. */
    const val END_GRACE_MS = 15 * 60_000L

    /** Only this much of the past is reconstructed after a long absence. */
    const val MAX_LOOKBACK_MS = 48 * 3_600_000L
}

fun minuteOfDay(hour: Int, minute: Int): Int = ((hour * 60 + minute) % 1440 + 1440) % 1440

fun formatMinute(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)
