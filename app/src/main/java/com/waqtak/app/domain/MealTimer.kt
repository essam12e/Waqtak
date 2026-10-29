package com.waqtak.app.domain

/**
 * Meal timer arithmetic. While the device stays up, elapsed-realtime (monotonic, unaffected
 * by clock changes) is authoritative. After a reboot the monotonic base is lost, so the saved
 * wall-clock start is used instead.
 */
data class TimerBase(
    val startWall: Long,
    val startElapsed: Long,
    val bootCount: Int,
    val totalMs: Long,
)

object MealTimer {
    fun elapsedMs(base: TimerBase, nowWall: Long, nowElapsed: Long, bootCount: Int): Long =
        if (base.bootCount == bootCount && bootCount >= 0 && nowElapsed >= base.startElapsed) {
            nowElapsed - base.startElapsed
        } else {
            (nowWall - base.startWall).coerceAtLeast(0)
        }

    fun remainingMs(base: TimerBase, nowWall: Long, nowElapsed: Long, bootCount: Int): Long =
        base.totalMs - elapsedMs(base, nowWall, nowElapsed, bootCount)

    /** Wall-clock instant at which the end alarm should fire given the current clocks. */
    fun endWall(base: TimerBase, nowWall: Long, nowElapsed: Long, bootCount: Int): Long =
        nowWall + remainingMs(base, nowWall, nowElapsed, bootCount)
}
