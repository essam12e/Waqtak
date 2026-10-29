package com.waqtak.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object TimeFmt {
    fun dayStart(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayStart(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(AppLocale.current())
            .format(Instant.ofEpochMilli(millis).atZone(zone))

    fun date(date: LocalDate): String =
        DateTimeFormatter.ofPattern("EEEE d MMMM", AppLocale.current()).format(date)

    fun shortDay(date: LocalDate): String =
        DateTimeFormatter.ofPattern("EEE d/M", AppLocale.current()).format(date)

    /** "in 2 h 15 min" style pieces for the next-event countdown. */
    fun splitMinutes(ms: Long): Pair<Long, Long> {
        val m = (ms.coerceAtLeast(0) + 59_999) / 60_000
        return m / 60 to m % 60
    }
}
