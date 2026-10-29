package com.waqtak.app

import android.Manifest
import android.app.AlarmManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.alarm.AlarmController
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.alarm.RingingHub
import com.waqtak.app.alarm.UserAction
import com.waqtak.app.domain.AlertKind
import com.waqtak.app.domain.MealType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Real alarms on the device after editing times, changing the time zone and moving the clock. */
@RunWith(AndroidJUnit4::class)
class ScheduleDeviceTest {
    private val ctx get() = DeviceUtils.ctx
    private val am get() = ctx.getSystemService(AlarmManager::class.java)
    private var originalZone: String = "UTC"

    @Before
    fun setUp() {
        DeviceUtils.grantAlarmPermissions()
        DeviceUtils.wakeAndUnlock()
        originalZone = ZoneId.systemDefault().id
        runBlocking {
            ctx.graph.repo.deleteAll()
            ctx.graph.repo.applyDefaultSchedule("test")
            // drink reminders off here, so only meal alarms are measured
            ctx.graph.settings.update { it.copy(onboardingDone = true, introDone = true, hydrationEnabled = false, lastReconcileAt = System.currentTimeMillis()) }
            AlarmScheduler.rescheduleAll(ctx)
        }
    }

    @After
    fun tearDown() {
        RingingHub.alerts.value.forEach { a -> runBlocking { AlarmController.perform(ctx, a.id, UserAction.SKIP) } }
        DeviceUtils.instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.SET_TIME_ZONE)
        try { am.setTimeZone(originalZone) } finally { DeviceUtils.instrumentation.uiAutomation.dropShellPermissionIdentity() }
        DeviceUtils.shell("settings put global auto_time 1")
    }

    private fun localMinute(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }

    @Test
    fun editedMeal_ringsOnceAtTheNewTime_andTheTimerStartsOnOk() = runBlocking<Unit> {
        val repo = ctx.graph.repo
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val target = localMinute(now + 2 * 60_000L, zone) // the next-but-one minute
        val lunch = ctx.graph.db.schedule().plans().first { it.type == MealType.LUNCH.name }
        repo.updatePlan(lunch.copy(minuteOfDay = (target + 30) % 1440)) // first edit
        AlarmScheduler.rescheduleAll(ctx)
        repo.updatePlan(ctx.graph.db.schedule().plan(lunch.id)!!.copy(minuteOfDay = target)) // second edit
        val plan = AlarmScheduler.rescheduleAll(ctx)
        assertEquals(target, localMinute(plan.mealStart!!, zone))
        assertEquals(plan.mealStart, am.nextAlarmClock.triggerTime) // the old alarm was replaced
        assertTrue(DeviceUtils.waitRinging(200_000))
        val alerts = RingingHub.alerts.value
        assertEquals(1, alerts.count { it.kind == AlertKind.MEAL_START })
        val alert = alerts.first()
        val pressedAt = System.currentTimeMillis()
        AlarmController.perform(ctx, alert.id, UserAction.START, durationMin = 25)
        val session = repo.openSessions().single()
        assertTrue("timer starts at the press, not at the alarm time", session.startWall >= pressedAt - 1_000)
        val end = AlarmScheduler.rescheduleAll(ctx).mealEnd
        assertNotNull("end-of-meal alarm must be scheduled", end)
        assertTrue(DeviceUtils.waitUntil(20_000) { RingingHub.alerts.value.isEmpty() })
        Thread.sleep(15_000)
        assertTrue("no duplicate ring after the edit", RingingHub.alerts.value.none { it.kind == AlertKind.MEAL_START })
        DeviceUtils.log("schedule_edit", "edited twice, rang once at ${java.time.LocalTime.ofSecondOfDay(target * 60L)}, end alarm at ${Instant.ofEpochMilli(end!!)}")
        repo.finishSession(session.id, com.waqtak.app.domain.EndReason.USER_FINISHED)
    }

    @Test
    fun timeZoneChange_reschedulesToLocalMealTimes() = runBlocking<Unit> {
        DeviceUtils.instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.SET_TIME_ZONE)
        try { am.setTimeZone("Asia/Tokyo") } finally { DeviceUtils.instrumentation.uiAutomation.dropShellPermissionIdentity() }
        val tokyo = ZoneId.of("Asia/Tokyo")
        assertTrue(DeviceUtils.waitUntil(15_000) {
            am.nextAlarmClock?.triggerTime?.let { localMinute(it, tokyo) in setOf(8 * 60, 13 * 60, 19 * 60) } == true
        })
        DeviceUtils.log("clock", "after zone change to Tokyo next alarm local=${java.time.LocalTime.ofSecondOfDay(localMinute(am.nextAlarmClock.triggerTime, tokyo) * 60L)}")
    }

    @Test
    fun clockMovedForward_noBacklogAndNextAlarmIsInTheFuture() = runBlocking<Unit> {
        DeviceUtils.shell("settings put global auto_time 0")
        val before = System.currentTimeMillis()
        DeviceUtils.instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.SET_TIME)
        try { am.setTime(before + 7 * 3_600_000L) } finally { DeviceUtils.instrumentation.uiAutomation.dropShellPermissionIdentity() }
        val zone = ZoneId.systemDefault()
        assertTrue(DeviceUtils.waitUntil(15_000) {
            val t = am.nextAlarmClock?.triggerTime ?: 0
            t > System.currentTimeMillis() && localMinute(t, zone) in setOf(8 * 60, 13 * 60, 19 * 60)
        })
        Thread.sleep(3_000)
        // meals skipped by the jump are not replayed; only one within the 30-minute grace may ring
        val newNow = System.currentTimeMillis()
        assertTrue("no replay of skipped alarms", RingingHub.alerts.value.all { it.scheduledAt >= newNow - 31 * 60_000L })
        DeviceUtils.log("clock", "clock +7h: next alarm ${Instant.ofEpochMilli(am.nextAlarmClock.triggerTime)}, nothing replayed")
        DeviceUtils.instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.SET_TIME)
        try { am.setTime(System.currentTimeMillis() - 7 * 3_600_000L) } finally { DeviceUtils.instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }
}
