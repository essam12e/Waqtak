package com.waqtak.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every clock time in the app is shown in 12-hour form, independent of the device setting:
 * Arabic "١:٠٠ م", English "1:00 PM"; midnight is 12:00 AM/ص, noon 12:00 PM/م.
 * Storage stays minute-of-day / epoch millis. Meal-timer durations stay mm:ss.
 */
object TimeFmt {
    fun amPm(isAm: Boolean, locale: Locale = AppLocale.current()): String =
        if (locale.language == "ar") (if (isAm) "ص" else "م") else (if (isAm) "AM" else "PM")

    fun clock(minuteOfDay: Int, locale: Locale = AppLocale.current()): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        val h24 = m / 60
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        return String.format(locale, "%d:%02d %s", h12, m % 60, amPm(h24 < 12, locale))
    }

    fun dayStart(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayStart(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = AppLocale.current()): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        return clock(t.hour * 60 + t.minute, locale)
    }

    fun date(date: LocalDate): String =
        DateTimeFormatter.ofPattern("EEEE d MMMM", AppLocale.current()).format(date)

    fun shortDay(date: LocalDate): String =
        DateTimeFormatter.ofPattern("d/M", AppLocale.current()).format(date)

    /** "in 2 h 15 min" style pieces for the next-event countdown. */
    fun splitMinutes(ms: Long): Pair<Long, Long> {
        val m = (ms.coerceAtLeast(0) + 59_999) / 60_000
        return m / 60 to m % 60
    }
}
