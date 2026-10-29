package com.waqtak.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.waqtak.app.domain.ScheduleMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs after scripts/device-tests.sh installed 1.0.0, completed its setup and logged a drink,
 * then installed this build over it (same package and signing key).
 */
@RunWith(AndroidJUnit4::class)
class UpgradeDeviceTest {
    private val ctx get() = DeviceUtils.ctx

    @Test
    fun dataKept_introNotShown_alarmsRescheduled() = runBlocking<Unit> {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        DeviceUtils.log("upgrade", "now version ${info.versionName} (${info.longVersionCode})")
        assertEquals(2L, info.longVersionCode)
        val g = ctx.graph
        val s = g.settings.get()
        assertTrue("setup from 1.0.0 kept", s.onboardingDone)
        assertTrue("existing users never see the first-run cards", s.introDone)
        assertEquals(ScheduleMode.MANUAL, s.scheduleMode) // 1.0.0 times are kept exactly
        val plans = g.db.schedule().plans().sortedBy { it.minuteOfDay }
        DeviceUtils.log("upgrade", "plans after upgrade: ${plans.map { it.type + "@" + it.minuteOfDay + "/" + it.durationMin + " days=" + it.daysMask }}")
        assertEquals(listOf(8 * 60, 13 * 60, 18 * 60), plans.map { it.minuteOfDay }) // what 1.0.0 created
        assertTrue(plans.all { it.daysMask == 127 })
        val drinks = g.db.drinks().all()
        DeviceUtils.log("upgrade", "drink log entries kept: ${drinks.size}")
        assertTrue("history kept", drinks.isNotEmpty())

        DeviceUtils.grantAlarmPermissions()
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val d = DeviceUtils.device
        val ar = com.waqtak.app.util.AppLocale.wrap(ctx, java.util.Locale("ar"))
        assertNotNull(d.wait(Until.findObject(By.text(ar.getString(R.string.home_today))), 15_000))
        assertNull(d.findObject(By.text(ar.getString(R.string.intro_1_title))))
        DeviceUtils.screenshot("21_after_upgrade_home")
        val plan = com.waqtak.app.alarm.AlarmScheduler.rescheduleAll(ctx)
        assertNotNull(plan.mealStart)
    }
}
