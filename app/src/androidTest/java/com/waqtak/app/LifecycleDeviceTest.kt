package com.waqtak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.data.PlanDraft
import com.waqtak.app.data.ProfileDraft
import com.waqtak.app.data.db.MealSessionEntity
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.ProfileKind
import com.waqtak.app.domain.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Multi-phase tests driven by scripts/device-tests.sh: each phase runs in a fresh process,
 * and the script kills the process / sends BOOT_COMPLETED between phases.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleDeviceTest {
    private val ctx get() = DeviceUtils.ctx

    @Test
    fun processDeath_phase1_startShortSessionThenDie() = runBlocking {
        DeviceUtils.grantAlarmPermissions()
        val g = ctx.graph
        val clock = g.repo.clock
        g.settings.update { it.copy(onboardingDone = true) }
        // a short session (40 s) inserted directly; the app UI itself only allows 5..90 minutes
        g.db.sessions().clear()
        g.db.sessions().insertIgnore(
            MealSessionEntity(
                occurrenceKey = "devicetest:${clock.now()}", mealType = MealType.LUNCH.name, label = "",
                startWall = clock.now(), startElapsed = clock.elapsed(), bootCount = clock.bootCount(),
                plannedMs = 40_000, state = SessionState.ACTIVE.name,
            )
        )
        val plan = AlarmScheduler.rescheduleAll(ctx)
        DeviceUtils.log("process_death", "phase1 end alarm at ${plan.mealEnd?.let { Instant.ofEpochMilli(it) }}")
        assertTrue(plan.mealEnd != null)
    }

    @Test
    fun processDeath_phase2_sessionSurvivedAndEndRangOnce() = runBlocking {
        val open = ctx.graph.repo.openSessions()
        DeviceUtils.log("process_death", "phase2 open sessions=${open.map { it.state }}")
        assertEquals(1, open.size)
        assertEquals(SessionState.END_RINGING.name, open.single().state)
        ctx.graph.repo.finishSession(open.single().id, com.waqtak.app.domain.EndReason.TIMER_ACK)
    }

    @Test
    fun boot_phase1_scheduleThenLoseAlarms() = runBlocking {
        DeviceUtils.grantAlarmPermissions()
        val g = ctx.graph
        val now = g.repo.clock.now()
        val zone = g.repo.clock.zone()
        val t = Instant.ofEpochMilli(now + 3 * 3_600_000L).atZone(zone)
        val minute = t.hour * 60 + t.minute
        g.repo.replaceSchedule(
            listOf(ProfileDraft(ProfileKind.WORK, "x", 0, 23 * 60 + 59, listOf(PlanDraft(MealType.DINNER, "", minute, 20)))),
            emptyMap(),
        )
        g.settings.update { it.copy(onboardingDone = true, lastReconcileAt = now) }
        AlarmScheduler.cancelAll(ctx) // what a reboot does to registered alarms
        assertTrue(!AlarmScheduler.isScheduled(ctx, AlarmScheduler.ACTION_MEAL_START, AlarmScheduler.RC_MEAL_START))
        DeviceUtils.log("boot", "phase1 alarms cleared, dinner at ${t.toLocalTime()}")
    }
}
