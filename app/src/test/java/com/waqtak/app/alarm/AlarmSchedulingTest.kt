package com.waqtak.app.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.FakeClock
import com.waqtak.app.MIN
import com.waqtak.app.at
import com.waqtak.app.domain.OccurrenceStatus
import com.waqtak.app.installGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlarmSchedulingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val am get() = ctx.getSystemService(AlarmManager::class.java)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun alarmsFor(action: String, rc: Int) = shadowOf(am).scheduledAlarms.filter {
        it.operation == AlarmScheduler.pendingIntent(ctx, action, rc, noCreate = true)
    }

    @Test
    fun mealAlarmUsesAlarmClock_andReschedulingNeverDuplicates() = runBlocking {
        installGraph()
        repeat(3) { AlarmScheduler.rescheduleAll(ctx) }
        val meal = alarmsFor(AlarmScheduler.ACTION_MEAL_START, AlarmScheduler.RC_MEAL_START)
        assertEquals(1, meal.size)
        assertEquals(at(8), meal[0].triggerAtMs)
        assertEquals(at(8), am.nextAlarmClock.triggerTime) // visible alarm clock => exact, Doze-proof
    }

    @Test
    fun snoozeReplacesTheOldAlarm() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val a = g.repo.claimDueMeals(clock.now()).single()
        g.repo.snoozeMeal(a.ref)
        AlarmScheduler.rescheduleAll(ctx)
        val meal = alarmsFor(AlarmScheduler.ACTION_MEAL_START, AlarmScheduler.RC_MEAL_START)
        assertEquals(1, meal.size)
        assertEquals(at(8, 10), meal[0].triggerAtMs)
    }

    @Test
    fun mealEndAlarmFollowsTheSession_andIsCancelledWhenFinished() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val a = g.repo.claimDueMeals(clock.now()).single()
        val id = g.repo.startMeal(a.ref, 20)!!
        AlarmScheduler.rescheduleAll(ctx)
        assertEquals(at(8, 20), alarmsFor(AlarmScheduler.ACTION_MEAL_END, AlarmScheduler.RC_MEAL_END).single().triggerAtMs)
        g.repo.extendSession(id)
        AlarmScheduler.rescheduleAll(ctx)
        assertEquals(at(8, 25), alarmsFor(AlarmScheduler.ACTION_MEAL_END, AlarmScheduler.RC_MEAL_END).single().triggerAtMs)
        g.repo.finishSession(id, com.waqtak.app.domain.EndReason.USER_FINISHED)
        AlarmScheduler.rescheduleAll(ctx)
        assertTrue(alarmsFor(AlarmScheduler.ACTION_MEAL_END, AlarmScheduler.RC_MEAL_END).isEmpty())
    }

    @Test
    fun exactAlarmPermissionDenied_fallsBackWithoutCrashing() = runBlocking {
        installGraph()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AlarmScheduler.rescheduleAll(ctx)
        val meal = alarmsFor(AlarmScheduler.ACTION_MEAL_START, AlarmScheduler.RC_MEAL_START)
        assertEquals(1, meal.size)
        assertNull(am.nextAlarmClock) // not an alarm clock without the permission
        // permission granted again -> reconcile restores the exact alarm
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        Reconciler.reconcile(ctx, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        assertNotNull(am.nextAlarmClock)
    }

    @Test
    fun duplicateMealAlarmDeliveries_startTheRingingServiceOnce() = runBlocking {
        val (_, clock) = installGraph()
        clock.nowMs = at(8)
        val app = shadowOf(ctx as android.app.Application)
        while (app.nextStartedService != null) Unit
        AlarmEvents.onMealStart(ctx)
        AlarmEvents.onMealStart(ctx)
        val started = generateSequence { app.nextStartedService }.toList()
        assertEquals(1, started.count { it.action == AlarmService.ACTION_RING })
    }

    @Test
    fun afterBoot_orphanRingingBecomesMissed_andNextAlarmIsScheduled() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val a = g.repo.claimDueMeals(clock.now()).single() // ringing when the phone died
        clock.nowMs = at(9); clock.boot = 2; clock.elapsedMs = 5_000
        Reconciler.reconcile(ctx, Intent.ACTION_BOOT_COMPLETED)
        assertEquals(OccurrenceStatus.MISSED.name, g.db.occurrences().get(a.ref)!!.status)
        assertEquals(at(13), alarmsFor(AlarmScheduler.ACTION_MEAL_START, AlarmScheduler.RC_MEAL_START).single().triggerAtMs)
    }

    @Test
    fun clockMovedForward_reconcileReportsMissedAndSchedulesNext() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(14) // TIME_SET: jumped past breakfast and lunch
        Reconciler.reconcile(ctx, Intent.ACTION_TIME_CHANGED)
        assertEquals(at(18), alarmsFor(AlarmScheduler.ACTION_MEAL_START, AlarmScheduler.RC_MEAL_START).single().triggerAtMs)
        assertEquals(2, g.db.occurrences().all().count { it.status == OccurrenceStatus.MISSED.name })
    }

    @Test
    fun drinkReminderIsScheduledDuringWakingHours() = runBlocking {
        val (_, clock) = installGraph(FakeClock(at(9)))
        AlarmScheduler.rescheduleAll(ctx)
        val drink = alarmsFor(AlarmScheduler.ACTION_DRINK, AlarmScheduler.RC_DRINK).single()
        // wake 07:00 + 90 min steps: 08:30, 10:00 ...
        assertEquals(at(10), drink.triggerAtMs)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(drink.triggerAtMs > clock.now())
    }

    @Test
    fun deletingAllDataCancelsAlarms() = runBlocking {
        val (g, _) = installGraph()
        AlarmScheduler.rescheduleAll(ctx)
        g.repo.deleteAll()
        AlarmScheduler.rescheduleAll(ctx)
        assertTrue(alarmsFor(AlarmScheduler.ACTION_MEAL_START, AlarmScheduler.RC_MEAL_START).isEmpty())
        assertTrue(alarmsFor(AlarmScheduler.ACTION_DRINK, AlarmScheduler.RC_DRINK).isEmpty())
        assertEquals(1, MIN / 60_000)
    }
}
