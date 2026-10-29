package com.waqtak.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MealTimerTest {
    private val base = TimerBase(startWall = 1_000_000L, startElapsed = 50_000L, bootCount = 7, totalMs = 20 * 60_000L)

    @Test
    fun usesMonotonicClockWhileDeviceStaysUp_evenIfWallClockJumps() {
        // 5 minutes elapsed; user moved the wall clock 2 hours forward
        val remaining = MealTimer.remainingMs(base, nowWall = 1_000_000L + 2 * 3_600_000L, nowElapsed = 50_000L + 5 * 60_000L, bootCount = 7)
        assertEquals(15 * 60_000L, remaining)
    }

    @Test
    fun afterRebootFallsBackToWallClock() {
        val remaining = MealTimer.remainingMs(base, nowWall = 1_000_000L + 8 * 60_000L, nowElapsed = 3_000L, bootCount = 8)
        assertEquals(12 * 60_000L, remaining)
    }

    @Test
    fun endWallIsRecomputedFromRemaining() {
        val end = MealTimer.endWall(base, nowWall = 5_000_000L, nowElapsed = 50_000L + 60_000L, bootCount = 7)
        assertEquals(5_000_000L + 19 * 60_000L, end)
    }

    @Test
    fun unknownBootCountUsesWallClock() {
        val b = base.copy(bootCount = -1)
        assertEquals(10 * 60_000L, MealTimer.remainingMs(b, 1_000_000L + 10 * 60_000L, 0, -1))
    }
}
