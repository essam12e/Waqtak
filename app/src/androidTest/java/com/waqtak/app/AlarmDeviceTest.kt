package com.waqtak.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.alarm.AlarmService
import com.waqtak.app.alarm.Notifications
import com.waqtak.app.alarm.RingingHub
import com.waqtak.app.ui.screens.readReadiness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real AlarmManager -> receiver -> foreground service -> alarm stream on an emulator.
 */
@RunWith(AndroidJUnit4::class)
class AlarmDeviceTest {
    private val ctx get() = DeviceUtils.ctx
    private val audio get() = ctx.getSystemService(AudioManager::class.java)

    @Before
    fun setUp() {
        DeviceUtils.grantAlarmPermissions()
        DeviceUtils.wakeAndUnlock()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) / 2 + 1, 0)
        runBlocking { ctx.graph.settings.update { it.copy(crescendo = false) } }
    }

    @After
    fun tearDown() {
        DeviceUtils.cancelTest()
        RingingHub.alerts.value.forEach { a -> runBlocking { com.waqtak.app.alarm.AlarmController.perform(ctx, a.id, com.waqtak.app.alarm.UserAction.TEST_OK) } }
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        DeviceUtils.shell("dumpsys deviceidle unforce")
        DeviceUtils.shell("dumpsys battery reset")
        DeviceUtils.wakeAndUnlock()
    }

    private fun alarmPlaybackActive(): Boolean = audio.activePlaybackConfigurations.any {
        it.audioAttributes.usage == AudioAttributes.USAGE_ALARM
    }

    @Test
    fun ringsOnAlarmStreamInSilentMode_andOkStopsImmediately() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        val ringer = audio.ringerMode
        DeviceUtils.log("silent", "ringerMode after request=$ringer (0=silent,1=vibrate,2=normal)")
        assertTrue(ringer != AudioManager.RINGER_MODE_NORMAL)
        AlarmScheduler.scheduleTest(ctx, 3_000)
        assertTrue("alarm did not ring", DeviceUtils.waitRinging())
        val service = AlarmService.instance
        assertNotNull(service)
        assertNotNull(service!!.playingAlertId)
        val playing = DeviceUtils.waitUntil(5_000) { alarmPlaybackActive() }
        DeviceUtils.log("silent", "USAGE_ALARM playback active while silent: $playing")
        assertTrue("no USAGE_ALARM playback in silent mode", playing)

        val id = RingingHub.alerts.value.first().id
        val t0 = System.nanoTime()
        DeviceUtils.instrumentation.runOnMainSync { com.waqtak.app.alarm.AlarmController.silenceNow(id) }
        val silencedMs = (System.nanoTime() - t0) / 1_000_000.0
        assertNull(service.playingAlertId)
        val stopped = DeviceUtils.waitUntil(2_000, 50) { !alarmPlaybackActive() }
        DeviceUtils.log("silent", "silence call took ${"%.1f".format(silencedMs)} ms; playback stopped=$stopped")
        assertTrue(stopped)
        runBlocking { com.waqtak.app.alarm.AlarmController.perform(ctx, id, com.waqtak.app.alarm.UserAction.TEST_OK) }
        assertTrue(DeviceUtils.waitUntil(5_000) { AlarmService.instance == null })
    }

    @Test
    fun lockedScreen_alarmShowsOverLockScreen_okStopsWithoutUnlocking() {
        DeviceUtils.device.sleep()
        Thread.sleep(1_500)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        AlarmScheduler.scheduleTest(ctx, 4_000)
        assertTrue(DeviceUtils.waitRinging())
        val ok = DeviceUtils.device.wait(Until.findObject(By.text(com.waqtak.app.util.AppLocale.wrap(ctx).getString(R.string.action_ok))), 15_000)
        DeviceUtils.screenshot("10_alarm_over_lockscreen")
        assertNotNull("alarm screen not shown over the lock screen", ok)
        assertTrue("device should be locked", km.isKeyguardLocked)
        ok.click()
        assertTrue(DeviceUtils.waitUntil(5_000) { RingingHub.alerts.value.isEmpty() })
        assertTrue(DeviceUtils.waitUntil(5_000) { AlarmService.instance == null })
        assertTrue("OK must not unlock the phone", km.isKeyguardLocked)
        DeviceUtils.log("lockscreen", "alarm shown over keyguard, OK stopped it, keyguard still locked")
    }

    @Test
    fun notificationActionStopsTheAlarm() {
        AlarmScheduler.scheduleTest(ctx, 2_000)
        assertTrue(DeviceUtils.waitRinging())
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val sbn = DeviceUtils.waitUntil(5_000) { nm.activeNotifications.any { it.id == Notifications.ID_RINGING } }
        assertTrue(sbn)
        val n = nm.activeNotifications.first { it.id == Notifications.ID_RINGING }.notification
        assertEquals(android.app.Notification.CATEGORY_ALARM, n.category)
        assertNotNull(n.fullScreenIntent)
        n.actions.first().actionIntent.send()
        assertTrue(DeviceUtils.waitUntil(5_000) { RingingHub.alerts.value.isEmpty() && AlarmService.instance == null })
        assertTrue(DeviceUtils.waitUntil(3_000) { nm.activeNotifications.none { it.id == Notifications.ID_RINGING } })
    }

    @Test
    fun alarmFiresInDoze() {
        DeviceUtils.shell("dumpsys battery unplug")
        DeviceUtils.device.sleep()
        val idle = DeviceUtils.shell("dumpsys deviceidle force-idle")
        DeviceUtils.log("doze", "force-idle: ${idle.trim()}")
        AlarmScheduler.scheduleTest(ctx, 5_000)
        val rang = DeviceUtils.waitRinging(40_000)
        DeviceUtils.log("doze", "state=${DeviceUtils.shell("dumpsys deviceidle get deep").trim()} rang=$rang")
        assertTrue("setAlarmClock alarm did not fire in Doze", rang)
    }

    @Test
    fun deniedPermissions_doNotCrash_andAreReported() {
        DeviceUtils.shell("appops set ${DeviceUtils.pkg} SCHEDULE_EXACT_ALARM deny")
        DeviceUtils.shell("pm revoke ${DeviceUtils.pkg} android.permission.POST_NOTIFICATIONS")
        Thread.sleep(500)
        val plan = runBlocking { AlarmScheduler.rescheduleAll(ctx) } // inexact fallback, no SecurityException
        val st = readReadiness(ctx)
        DeviceUtils.log("denied", "exact=${st.exact} notifications=${st.notifications} plan=$plan")
        assertFalse(st.exact)
        assertFalse(st.notifications)
        DeviceUtils.grantAlarmPermissions()
        assertTrue(readReadiness(ctx).exact)
    }
}
