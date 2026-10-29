package com.waqtak.app.alarm

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.at
import com.waqtak.app.domain.Alert
import com.waqtak.app.domain.AlertKind
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.SessionState
import com.waqtak.app.installGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlarmServiceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ringIntent(a: Alert) = AlarmService.putAlert(Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_RING), a)

    @Test
    fun ringingIsForeground_okStopsSoundVibrationAndWakeLock_serviceStops() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val alert = g.repo.claimDueMeals(clock.now()).single()
        val controller = Robolectric.buildService(AlarmService::class.java, ringIntent(alert)).create().startCommand(0, 1)
        val service = controller.get()
        waitFor { RingingHub.alerts.value.isNotEmpty() }
        assertEquals(listOf(alert.id), RingingHub.alerts.value.map { it.id })
        val shadowService = shadowOf(service)
        assertNotNull(shadowService.lastForegroundNotification)
        assertEquals(alert.id, service.playingAlertId)
        assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld == true)

        // duplicate delivery (two receivers / retries) does not add a second alert
        controller.withIntent(ringIntent(alert)).startCommand(0, 2)
        waitFor { false }
        assertEquals(1, RingingHub.alerts.value.size)

        AlarmController.perform(ctx, alert.id, UserAction.START, durationMin = 15)
        waitFor { RingingHub.alerts.value.isEmpty() }
        assertNull(service.playingAlertId)
        assertFalse(ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
        assertTrue(shadowService.isStoppedBySelf)
        val session = g.repo.openSessions().single()
        assertEquals(15 * 60_000L, session.plannedMs)
        assertEquals(SessionState.ACTIVE.name, session.state)

        // a second tap (screen + notification) changes nothing
        AlarmController.perform(ctx, alert.id, UserAction.START, durationMin = 15)
        assertEquals(1, g.repo.openSessions().size)
    }

    @Test
    fun mealEndPreemptsMealStart_withOneSoundAtATime() = runBlocking {
        installGraph()
        val start = Alert("start:x", AlertKind.MEAL_START, MealType.LUNCH, "", at(13), "x", 25)
        val end = Alert("end:1", AlertKind.MEAL_END, MealType.BREAKFAST, "", at(13), "1", 20)
        val controller = Robolectric.buildService(AlarmService::class.java, ringIntent(start)).create().startCommand(0, 1)
        waitFor { RingingHub.alerts.value.isNotEmpty() }
        controller.withIntent(ringIntent(end)).startCommand(0, 2)
        waitFor { RingingHub.alerts.value.size == 2 }
        assertEquals("end:1", RingingHub.alerts.value.first().id)
        assertEquals("end:1", controller.get().playingAlertId)
        controller.get().resolve("end:1")
        waitFor { RingingHub.alerts.value.size == 1 }
        assertEquals("start:x", RingingHub.alerts.value.single().id)
        assertEquals("start:x", controller.get().playingAlertId)
        controller.get().resolve("start:x")
        waitFor { RingingHub.alerts.value.isEmpty() }
        assertFalse(ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
    }

    @Test
    fun notificationActionStopsTheSoundSynchronously() = runBlocking {
        installGraph()
        val test = Alert("test:1", AlertKind.TEST, null, "", 1L, "1")
        val controller = Robolectric.buildService(AlarmService::class.java, ringIntent(test)).create().startCommand(0, 1)
        waitFor { RingingHub.alerts.value.isNotEmpty() }
        assertEquals("test:1", controller.get().playingAlertId)
        // the receiver silences before doing any I/O
        NotificationActionReceiver().onReceive(
            ctx,
            Intent(NotificationActionReceiver.ACTION_TEST_OK).putExtra(NotificationActionReceiver.EXTRA_ALERT, "test:1"),
        )
        assertNull(controller.get().playingAlertId) // silenced before any database work
        assertFalse(ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
        waitFor { RingingHub.alerts.value.isEmpty() }
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun destroyReleasesEverything() = runBlocking {
        installGraph()
        val test = Alert("test:2", AlertKind.TEST, null, "", 2L, "2")
        val controller = Robolectric.buildService(AlarmService::class.java, ringIntent(test)).create().startCommand(0, 1)
        waitFor { RingingHub.alerts.value.isNotEmpty() }
        controller.destroy()
        assertNull(AlarmService.instance)
        assertTrue(RingingHub.alerts.value.isEmpty())
        assertFalse(ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
        assertEquals(PowerManager.PARTIAL_WAKE_LOCK, PowerManager.PARTIAL_WAKE_LOCK)
    }
}
