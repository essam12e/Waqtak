package com.waqtak.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class ProfileSpec(val id: Long, val wakeMinute: Int, val sleepMinute: Int)

data class PlanSpec(
    val id: Long,
    val profileId: Long,
    val type: MealType,
    val label: String,
    val minuteOfDay: Int,
    val durationMin: Int,
    val enabled: Boolean,
    val effectiveFrom: Long,
    val daysMask: Int = Days.ALL,
)

/** "Today only" time change for one occurrence; ignored if the new time was already past when set. */
data class TimeOverride(val atMillis: Long, val effectiveFrom: Long)

data class DayAssignment(
    val defaultProfileId: Long,
    val byWeekday: Map<DayOfWeek, Long> = emptyMap(),
    val overrides: Map<LocalDate, Long> = emptyMap(),
) {
    fun profileFor(date: LocalDate): Long = overrides[date] ?: byWeekday[date.dayOfWeek] ?: defaultProfileId
}

data class Occurrence(
    val key: String,
    val planId: Long,
    val type: MealType,
    val label: String,
    val durationMin: Int,
    /** Calendar date on which this waking day started (the wake-up date). */
    val dayDate: LocalDate,
    val atMillis: Long,
)

data class WakingWindow(val dayDate: LocalDate, val wakeAt: Long, val sleepAt: Long)

/**
 * Expands plan rows into concrete instants. A "waking day" starts at the profile's wake
 * time on date D; any plan time earlier than the wake time falls on D+1 (after midnight).
 * Local times are resolved with the zone rules, so DST gaps shift forward and overlaps
 * use the earlier offset (java.time semantics).
 */
object OccurrencePlanner {
    fun key(planId: Long, dayDate: LocalDate) = "$planId@${dayDate.toEpochDay()}"

    private fun at(date: LocalDate, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), zone).toInstant().toEpochMilli()

    fun occurrences(
        fromMillis: Long,
        toMillis: Long,
        zone: ZoneId,
        profiles: Map<Long, ProfileSpec>,
        plans: List<PlanSpec>,
        assignment: DayAssignment,
        timeOverrides: Map<String, TimeOverride> = emptyMap(),
    ): List<Occurrence> {
        val fromDate = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate().minusDays(1)
        val toDate = Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate()
        val byProfile = plans.filter { it.enabled }.groupBy { it.profileId }
        val out = LinkedHashMap<String, Occurrence>()
        var d = fromDate
        while (!d.isAfter(toDate)) {
            val profile = profiles[assignment.profileFor(d)]
            if (profile != null) {
                for (p in byProfile[profile.id].orEmpty()) {
                    if (!Days.has(p.daysMask, d.dayOfWeek)) continue
                    val k = key(p.id, d)
                    val override = timeOverrides[k]
                    val t: Long
                    if (override != null) {
                        if (override.atMillis < override.effectiveFrom) continue
                        t = override.atMillis
                    } else {
                        val date = if (p.minuteOfDay >= profile.wakeMinute) d else d.plusDays(1)
                        t = at(date, p.minuteOfDay, zone)
                        if (t < p.effectiveFrom) continue
                    }
                    if (t in fromMillis until toMillis) {
                        out[k] = Occurrence(k, p.id, p.type, p.label, p.durationMin, d, t)
                    }
                }
            }
            d = d.plusDays(1)
        }
        return out.values.sortedWith(compareBy({ it.atMillis }, { it.type.order }))
    }

    /** Instant of a minute-of-day inside the waking day that starts on [dayDate]. */
    fun atInWakingDay(dayDate: LocalDate, profile: ProfileSpec, minuteOfDay: Int, zone: ZoneId): Long {
        val date = if (minuteOfDay >= profile.wakeMinute) dayDate else dayDate.plusDays(1)
        return at(date, minuteOfDay, zone)
    }

    /** Instant of a minute-of-day on a calendar date. */
    fun atOnDate(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Long = at(date, minuteOfDay % 1440, zone)

    fun wakingWindow(date: LocalDate, profile: ProfileSpec, zone: ZoneId): WakingWindow {
        val wakeAt = at(date, profile.wakeMinute, zone)
        val sleepDate = if (profile.sleepMinute > profile.wakeMinute) date else date.plusDays(1)
        return WakingWindow(date, wakeAt, at(sleepDate, profile.sleepMinute, zone))
    }

    /** Waking windows that overlap [fromMillis, toMillis). */
    fun wakingWindows(
        fromMillis: Long,
        toMillis: Long,
        zone: ZoneId,
        profiles: Map<Long, ProfileSpec>,
        assignment: DayAssignment,
    ): List<WakingWindow> {
        val fromDate = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate().minusDays(1)
        val toDate = Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate()
        val out = mutableListOf<WakingWindow>()
        var d = fromDate
        while (!d.isAfter(toDate)) {
            profiles[assignment.profileFor(d)]?.let { p ->
                val w = wakingWindow(d, p, zone)
                if (w.sleepAt > fromMillis && w.wakeAt < toMillis) out += w
            }
            d = d.plusDays(1)
        }
        return out
    }
}
