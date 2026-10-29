package com.waqtak.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class OccurrencePlannerTest {
    private val riyadh = ZoneId.of("Asia/Riyadh")
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun ms(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), zone).toInstant().toEpochMilli()

    private fun plan(id: Long, profile: Long, type: MealType, minute: Int, enabled: Boolean = true) =
        PlanSpec(id, profile, type, "", minute, 20, enabled, effectiveFrom = 0)

    @Test
    fun mealAfterMidnightBelongsToPreviousWakingDay() {
        val profiles = mapOf(1L to ProfileSpec(1, 16 * 60, 8 * 60))
        val plans = listOf(plan(10, 1, MealType.BREAKFAST, 17 * 60), plan(11, 1, MealType.DINNER, 3 * 60))
        val from = ms(riyadh, 2026, 3, 10, 12)
        val to = ms(riyadh, 2026, 3, 11, 12)
        val occ = OccurrencePlanner.occurrences(from, to, riyadh, profiles, plans, DayAssignment(1))
        assertEquals(2, occ.size)
        assertEquals(ms(riyadh, 2026, 3, 10, 17), occ[0].atMillis)
        assertEquals(ms(riyadh, 2026, 3, 11, 3), occ[1].atMillis)
        // both belong to the waking day that started on March 10
        assertTrue(occ.all { it.dayDate == LocalDate.of(2026, 3, 10) })
        assertEquals(occ[1].key, OccurrencePlanner.key(11, LocalDate.of(2026, 3, 10)))
    }

    @Test
    fun keysAreStableAcrossRecomputation() {
        val profiles = mapOf(1L to ProfileSpec(1, 7 * 60, 23 * 60))
        val plans = listOf(plan(1, 1, MealType.LUNCH, 13 * 60))
        val a = OccurrencePlanner.occurrences(ms(riyadh, 2026, 1, 1, 0), ms(riyadh, 2026, 1, 3, 0), riyadh, profiles, plans, DayAssignment(1))
        val b = OccurrencePlanner.occurrences(ms(riyadh, 2026, 1, 1, 14), ms(riyadh, 2026, 1, 3, 0), riyadh, profiles, plans, DayAssignment(1))
        assertEquals(a.map { it.key }.drop(1), b.map { it.key })
    }

    @Test
    fun dstSpringForward_gapTimeShiftsForward_andOtherMealsKeepLocalTime() {
        // Europe/Berlin 2026-03-29 02:00 -> 03:00
        val profiles = mapOf(1L to ProfileSpec(1, 1 * 60, 20 * 60))
        val plans = listOf(plan(1, 1, MealType.BREAKFAST, 2 * 60 + 30), plan(2, 1, MealType.LUNCH, 13 * 60))
        val occ = OccurrencePlanner.occurrences(ms(berlin, 2026, 3, 29, 0), ms(berlin, 2026, 3, 29, 23), berlin, profiles, plans, DayAssignment(1))
        assertEquals(2, occ.size)
        assertEquals(ms(berlin, 2026, 3, 29, 3, 30), occ[0].atMillis)
        assertEquals(13, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(occ[1].atMillis), berlin).hour)
    }

    @Test
    fun dstFallBack_mealsStayOnLocalWallTime() {
        // 2026-10-25 03:00 -> 02:00
        val profiles = mapOf(1L to ProfileSpec(1, 7 * 60, 23 * 60))
        val plans = listOf(plan(1, 1, MealType.LUNCH, 13 * 60))
        val occ = OccurrencePlanner.occurrences(ms(berlin, 2026, 10, 24, 0), ms(berlin, 2026, 10, 26, 23), berlin, profiles, plans, DayAssignment(1))
        assertEquals(3, occ.size)
        occ.forEach { assertEquals(13, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.atMillis), berlin).hour) }
        // the day with the extra hour is 25 h long
        assertEquals(25 * 3_600_000L, occ[1].atMillis - occ[0].atMillis)
    }

    @Test
    fun weekdayAssignmentAndDateOverride() {
        val profiles = mapOf(1L to ProfileSpec(1, 6 * 60, 22 * 60), 2L to ProfileSpec(2, 9 * 60, 24 * 60 - 1))
        val plans = listOf(plan(1, 1, MealType.BREAKFAST, 7 * 60), plan(2, 2, MealType.BREAKFAST, 10 * 60))
        val friday = LocalDate.of(2026, 1, 2) // Friday
        val assign = DayAssignment(1, mapOf(DayOfWeek.FRIDAY to 2L), mapOf(LocalDate.of(2026, 1, 3) to 2L))
        val occ = OccurrencePlanner.occurrences(ms(riyadh, 2026, 1, 1, 0), ms(riyadh, 2026, 1, 4, 23), riyadh, profiles, plans, assign)
        val byDay = occ.associate { it.dayDate to it.planId }
        assertEquals(1L, byDay[LocalDate.of(2026, 1, 1)])
        assertEquals(2L, byDay[friday])
        assertEquals(2L, byDay[LocalDate.of(2026, 1, 3)]) // override on Saturday
        assertEquals(1L, byDay[LocalDate.of(2026, 1, 4)])
    }

    @Test
    fun disabledPlansAndPlansBeforeEffectiveFromAreIgnored() {
        val profiles = mapOf(1L to ProfileSpec(1, 7 * 60, 23 * 60))
        val eff = ms(riyadh, 2026, 1, 1, 12, 30)
        val plans = listOf(
            plan(1, 1, MealType.LUNCH, 12 * 60).copy(effectiveFrom = eff),
            plan(2, 1, MealType.DINNER, 19 * 60, enabled = false),
        )
        val occ = OccurrencePlanner.occurrences(ms(riyadh, 2026, 1, 1, 0), ms(riyadh, 2026, 1, 2, 23), riyadh, profiles, plans, DayAssignment(1))
        assertEquals(listOf(ms(riyadh, 2026, 1, 2, 12)), occ.map { it.atMillis })
    }

    @Test
    fun repeatDaysSkipUnselectedWeekdays() {
        val profiles = mapOf(1L to ProfileSpec(1, 0, 0))
        // Monday..Friday only (bits 0..4)
        val plans = listOf(plan(1, 1, MealType.LUNCH, 13 * 60).copy(daysMask = 0b0011111))
        // 2026-01-01 is a Thursday; range Thu..Sun
        val occ = OccurrencePlanner.occurrences(ms(riyadh, 2026, 1, 1, 0), ms(riyadh, 2026, 1, 5, 0), riyadh, profiles, plans, DayAssignment(1))
        assertEquals(listOf(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), occ.map { it.dayDate.dayOfWeek })
    }

    @Test
    fun thisDayOnlyOverrideMovesOneOccurrence_andPastOverridesNeverRing() {
        val profiles = mapOf(1L to ProfileSpec(1, 0, 0))
        val plans = listOf(plan(1, 1, MealType.LUNCH, 13 * 60))
        val key = OccurrencePlanner.key(1, LocalDate.of(2026, 1, 1))
        val moved = mapOf(key to TimeOverride(ms(riyadh, 2026, 1, 1, 15), effectiveFrom = ms(riyadh, 2026, 1, 1, 9)))
        val occ = OccurrencePlanner.occurrences(ms(riyadh, 2026, 1, 1, 0), ms(riyadh, 2026, 1, 3, 0), riyadh, profiles, plans, DayAssignment(1), moved)
        assertEquals(listOf(ms(riyadh, 2026, 1, 1, 15), ms(riyadh, 2026, 1, 2, 13)), occ.map { it.atMillis })
        // set at 16:00 to 15:00 -> already past when set -> skipped
        val past = mapOf(key to TimeOverride(ms(riyadh, 2026, 1, 1, 15), effectiveFrom = ms(riyadh, 2026, 1, 1, 16)))
        val occ2 = OccurrencePlanner.occurrences(ms(riyadh, 2026, 1, 1, 0), ms(riyadh, 2026, 1, 3, 0), riyadh, profiles, plans, DayAssignment(1), past)
        assertEquals(listOf(ms(riyadh, 2026, 1, 2, 13)), occ2.map { it.atMillis })
    }

    @Test
    fun wakingWindowCrossesMidnight() {
        val w = OccurrencePlanner.wakingWindow(LocalDate.of(2026, 1, 1), ProfileSpec(1, 10 * 60, 2 * 60), riyadh)
        assertEquals(ms(riyadh, 2026, 1, 1, 10), w.wakeAt)
        assertEquals(ms(riyadh, 2026, 1, 2, 2), w.sleepAt)
    }
}
