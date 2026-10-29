package com.waqtak.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HydrationPlannerTest {
    private val H = 3_600_000L
    private val M = 60_000L
    private val w = WakingWindow(LocalDate.of(2026, 1, 1), wakeAt = 0, sleepAt = 16 * H)

    @Test
    fun remindersSpreadAcrossWakingHours_quietBeforeSleep() {
        val t = HydrationPlanner.reminderTimes(w, 90, emptyList())
        assertEquals(90 * M, t.first())
        assertTrue(t.last() <= 15 * H)
        assertTrue(t.zipWithNext { a, b -> b - a }.all { it == 90 * M })
    }

    @Test
    fun reminderNearMealIsMerged() {
        val meal = 3 * H + 10 * M // reminder at 3h falls 10 min before the meal
        val t = HydrationPlanner.reminderTimes(w, 90, listOf(meal))
        assertFalse(t.contains(3 * H))
        assertTrue(t.contains(90 * M))
    }

    @Test
    fun noBacklogAfterLongAbsence() {
        // device was off; now is 10h after wake; handledUpTo far in the past
        val next = HydrationPlanner.next(10 * H, listOf(w), 90, emptyList(), handledUpTo = 0, snoozeUntil = 0)
        assertEquals(10 * H + 30 * M, next) // only the next future slot (10.5 h)
    }

    @Test
    fun snoozeIsUsedWhenEarlier() {
        val next = HydrationPlanner.next(2 * H, listOf(w), 90, emptyList(), handledUpTo = 90 * M, snoozeUntil = 2 * H + 15 * M)
        assertEquals(2 * H + 15 * M, next)
    }

    @Test
    fun nothingAfterTheLastSlot() {
        assertNull(HydrationPlanner.next(15 * H + 30 * M, listOf(w), 90, emptyList(), 0, 0))
    }

    @Test
    fun manualWindow_every90min_from8amTo9pm() {
        val start = 8 * H
        val end = 21 * H
        val t = HydrationPlanner.intervalTimes(start, end, 90)
        assertEquals(8 * H, t.first())
        assertEquals(20 * H, t.last()) // 21:30 would be after 9 PM
        assertEquals(9, t.size)
        // the 8:00 reminder merges into breakfast at 8:00
        assertFalse(HydrationPlanner.mergeWithMeals(t, listOf(8 * H)).contains(8 * H))
    }

    @Test
    fun nextOf_neverReplaysBacklog() {
        val t = listOf(1 * H, 2 * H, 3 * H)
        assertEquals(3 * H, HydrationPlanner.nextOf(now = 2 * H + 1, times = t, handledUpTo = 0, snoozeUntil = 0))
        assertNull(HydrationPlanner.nextOf(now = 4 * H, times = t, handledUpTo = 0, snoozeUntil = 0))
    }

    @Test
    fun recentlyDrank_lightensReminders() {
        assertTrue(HydrationPlanner.recentlyDrank(now = 100 * M, lastDrinkAt = 80 * M, intervalMin = 90))
        assertFalse(HydrationPlanner.recentlyDrank(now = 200 * M, lastDrinkAt = 80 * M, intervalMin = 90))
        assertFalse(HydrationPlanner.recentlyDrank(now = 200 * M, lastDrinkAt = null, intervalMin = 90))
    }
}
