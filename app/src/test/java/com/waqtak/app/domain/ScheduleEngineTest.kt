package com.waqtak.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleEngineTest {
    private val three = listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)
    private fun h(hh: Int, mm: Int = 0) = hh * 60 + mm

    @Test
    fun regularDay_firstMealOneHourAfterWake_spacingWithinFourToFiveHours() {
        val p = ScheduleEngine.propose(h(7), h(23), three, reflux = false)
        assertEquals(listOf(h(8), h(13), h(18)), p.meals.map { it.minuteOfDay })
        assertTrue(p.dropped.isEmpty())
        // last meal (18:00 + 20 min) ends well before 21:00 (2 h before sleep)
        assertTrue(p.meals.last().minuteOfDay + 20 <= h(21))
    }

    @Test
    fun sleepAfterMidnight_mealsFollowTheWakingDay() {
        // wake 10:00, sleep 02:00 next day
        val p = ScheduleEngine.propose(h(10), h(2), three, reflux = false)
        assertTrue(ScheduleEngine.Note.CROSSES_MIDNIGHT in p.notes)
        val times = p.meals.map { it.minuteOfDay }
        assertEquals(h(11), times[0])
        val rel = times.map { ScheduleEngine.relative(h(10), it) }
        assertEquals(rel.sorted(), rel)
        // last meal ends at least 2 h before 02:00 sleep
        val awake = ScheduleEngine.awakeMinutes(h(10), h(2))
        assertTrue(rel.last() + 20 <= awake - 120)
    }

    @Test
    fun nightShift_dayCrossesMidnightWithMealAfterMidnight() {
        // wake 16:00, sleep 08:00
        val p = ScheduleEngine.propose(h(16), h(8), three, reflux = false)
        assertEquals(listOf(h(17), h(22), h(3)), p.meals.map { it.minuteOfDay })
    }

    @Test
    fun reflux_lastMealEndsAboutThreeHoursBeforeSleep() {
        val p = ScheduleEngine.propose(h(7), h(22), three, reflux = true)
        assertTrue(ScheduleEngine.Note.REFLUX_GAP_APPLIED in p.notes)
        val last = p.meals.last()
        assertTrue(last.minuteOfDay + last.durationMin <= h(19))
    }

    @Test
    fun shortDay_keepsEveryMeal_andSuggestsChoosingFewer() {
        // 8 waking hours: lunch is NOT removed; the user is told they may untick a meal
        val p = ScheduleEngine.propose(h(8), h(16), three, reflux = false)
        assertTrue(ScheduleEngine.Note.SHORT_DAY in p.notes)
        assertEquals(three, p.meals.map { it.type })
        assertTrue(p.dropped.isEmpty())
        val gaps = p.meals.zipWithNext { a, b -> b.minuteOfDay - a.minuteOfDay }
        assertTrue(gaps.all { it >= ScheduleEngine.MIN_SHORT_SPACING })
    }

    @Test
    fun snackIsKeptToo() {
        val p = ScheduleEngine.propose(h(7), h(19), three + MealType.SNACK, reflux = false)
        assertEquals(4, p.meals.size)
        assertTrue(p.dropped.isEmpty())
    }

    @Test
    fun wakeInEveningSleepInMorning() {
        // wake 18:00, sleep 10:00 (night worker): meals 19:00, 00:00, 05:00 crossing midnight
        val p = ScheduleEngine.propose(h(18), h(10), three, reflux = false)
        assertEquals(listOf(h(19), h(0), h(5)), p.meals.map { it.minuteOfDay })
        assertTrue(ScheduleEngine.Note.CROSSES_MIDNIGHT in p.notes)
    }

    @Test
    fun tooShortDay_isInvalid() {
        val p = ScheduleEngine.propose(h(8), h(11), three, reflux = false)
        assertTrue(ScheduleEngine.Note.INVALID_TIMES in p.notes)
        assertTrue(p.meals.isEmpty())
    }

    @Test
    fun longDay_spacingCappedAtFiveHours() {
        val p = ScheduleEngine.propose(h(5), h(23, 30), three, reflux = false)
        val gaps = p.meals.zipWithNext { a, b -> b.minuteOfDay - a.minuteOfDay }
        assertTrue(gaps.all { it <= ScheduleEngine.MAX_SPACING })
    }

    @Test
    fun customDurationsAreRespected() {
        val p = ScheduleEngine.propose(h(7), h(23), three, false, mapOf(MealType.LUNCH to 40))
        assertEquals(40, p.meals.first { it.type == MealType.LUNCH }.durationMin)
    }

    @Test
    fun review_flagsMealOutsideWakingHoursAndCloseToSleep() {
        val issues = ScheduleEngine.review(
            h(7), h(23),
            listOf(ScheduleEngine.MealSlot(1, h(8), 20), ScheduleEngine.MealSlot(2, h(22), 20), ScheduleEngine.MealSlot(3, h(23, 30), 20)),
            reflux = false,
        )
        assertTrue(issues[1] == null)
        assertTrue(ScheduleEngine.Issue.CLOSE_TO_SLEEP in issues[2]!!)
        assertTrue(ScheduleEngine.Issue.OUTSIDE_WAKING_HOURS in issues[3]!!)
    }
}
