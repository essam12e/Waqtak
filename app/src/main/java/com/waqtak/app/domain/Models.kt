package com.waqtak.app.domain

enum class MealType {
    BREAKFAST, LUNCH, SNACK, DINNER;

    /** Canonical order used when distributing meals across the waking day. */
    val order: Int get() = ordinal

    companion object {
        fun parse(value: String?): MealType = entries.firstOrNull { it.name == value } ?: LUNCH
    }
}

/**
 * AUTO: meal and drink times are computed from wake/sleep and recomputed when they change.
 * MANUAL: the user sets every time and duration; nothing is redistributed automatically.
 */
enum class ScheduleMode {
    AUTO, MANUAL;

    companion object {
        /** Values from version 1 (REGULAR/SHIFTS/CUSTOM) map to MANUAL so existing times are kept exactly. */
        fun parse(value: String?): ScheduleMode = if (value == AUTO.name) AUTO else MANUAL
    }
}

/** ALARM rings on the alarm stream until the user responds; LIGHT is a normal notification. */
enum class DrinkReminderStyle {
    ALARM, LIGHT;

    companion object {
        fun parse(value: String?): DrinkReminderStyle = entries.firstOrNull { it.name == value } ?: ALARM
    }
}

/** Manual drink reminders: every N minutes inside a window, or at fixed times. */
enum class DrinkScheduleMode {
    INTERVAL, TIMES;

    companion object {
        fun parse(value: String?): DrinkScheduleMode = entries.firstOrNull { it.name == value } ?: INTERVAL
    }
}

/** Repeat days as a 7-bit mask, bit 0 = Monday ... bit 6 = Sunday. */
object Days {
    const val ALL = 0b1111111

    fun has(mask: Int, day: java.time.DayOfWeek): Boolean = mask and (1 shl (day.value - 1)) != 0

    fun toggle(mask: Int, day: java.time.DayOfWeek): Int = mask xor (1 shl (day.value - 1))
}

/** The ready-made first-run schedule. Organisational starting points, not medical ideals. */
object DefaultSchedule {
    data class Meal(val type: MealType, val minuteOfDay: Int, val durationMin: Int)

    val meals = listOf(
        Meal(MealType.BREAKFAST, 8 * 60, 20),
        Meal(MealType.LUNCH, 13 * 60, 25),
        Meal(MealType.DINNER, 19 * 60, 20),
    )
    const val WAKE = 7 * 60
    const val SLEEP = 23 * 60
    const val DRINK_START = 8 * 60
    const val DRINK_END = 21 * 60
    const val DRINK_INTERVAL = 90
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
    const val DRINK_SNOOZE_MINUTES = 15
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

/** 24-hour form for logs and storage-adjacent debugging only; the UI always uses 12-hour time (TimeFmt). */
fun formatMinute(m: Int): String = "%02d:%02d".format(java.util.Locale.ROOT, (m / 60) % 24, m % 60)
