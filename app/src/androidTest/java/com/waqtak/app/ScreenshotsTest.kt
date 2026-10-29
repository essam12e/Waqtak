package com.waqtak.app

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.waqtak.app.alarm.AlarmController
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.alarm.RingingHub
import com.waqtak.app.alarm.UserAction
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.MealType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Walks the real app (Arabic) and saves screenshots; also proves the main flows render without crashing. */
@RunWith(AndroidJUnit4::class)
class ScreenshotsTest {
    private val ctx get() = DeviceUtils.ctx
    private val d get() = DeviceUtils.device
    private fun s(id: Int) = com.waqtak.app.util.AppLocale.wrap(ctx, java.util.Locale("ar")).getString(id)
    private fun sEn(id: Int) = com.waqtak.app.util.AppLocale.wrap(ctx, java.util.Locale.ENGLISH).getString(id)

    private fun launch(route: String? = null) {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        route?.let { i.putExtra(MainActivity.EXTRA_ROUTE, it) }
        ctx.startActivity(i)
        d.waitForIdle()
        Thread.sleep(1500)
    }

    private fun clickText(id: Int) {
        val text = s(id)
        var o = d.wait(Until.findObject(By.text(text)), 6_000) ?: d.findObject(By.text(sEn(id)))
        if (o == null && android.os.Build.VERSION.SDK_INT >= 34) {
            // UiAutomator's accessibility node cache can go stale between Compose recompositions
            DeviceUtils.instrumentation.uiAutomation.clearCache()
            o = d.wait(Until.findObject(By.text(text)), 6_000)
        }
        if (o == null) {
            val out = java.io.ByteArrayOutputStream()
            d.dumpWindowHierarchy(out)
            DeviceUtils.log("hierarchy_on_failure", out.toString())
            DeviceUtils.screenshot("zz_failure")
        }
        assertNotNull("not found: $text", o)
        o.click()
        d.waitForIdle()
        Thread.sleep(600)
    }

    @Test
    fun walkThroughTheApp() {
        DeviceUtils.grantAlarmPermissions()
        DeviceUtils.wakeAndUnlock()
        DeviceUtils.instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar")) }
        runBlocking { ctx.graph.repo.deleteAll() }

        runBlocking { ctx.graph.settings.clear(); ctx.graph.settings.update { it.copy(introDone = false) } }
        launch()
        DeviceUtils.screenshot("01_intro_1")
        clickText(R.string.action_next)
        DeviceUtils.screenshot("02_intro_2")
        clickText(R.string.action_next)
        DeviceUtils.screenshot("03_intro_3")
        clickText(R.string.action_begin)
        DeviceUtils.screenshot("04_setup_default")
        clickText(R.string.action_edit_times)
        DeviceUtils.screenshot("05_setup_editing")
        clickText(R.string.action_done_editing)
        clickText(R.string.action_activate_mine)
        DeviceUtils.screenshot("06_setup_permissions")
        clickText(R.string.action_begin)
        Thread.sleep(1500)
        // keep scheduled drink alarms from popping up during the walk (settings stay as shown)
        runBlocking { ctx.graph.settings.update { it.copy(drinkHandledUpTo = System.currentTimeMillis() + 3_600_000L) } }
        // the default schedule was activated as shown
        val plans = runBlocking { ctx.graph.db.schedule().plans() }.sortedBy { it.minuteOfDay }
        org.junit.Assert.assertEquals(listOf(8 * 60, 13 * 60, 19 * 60), plans.map { it.minuteOfDay })
        org.junit.Assert.assertEquals(listOf(20, 25, 20), plans.map { it.durationMin })
        // the intro is never shown again on a new launch
        launch()
        org.junit.Assert.assertNull(d.findObject(By.text(s(R.string.intro_1_title))))

        runBlocking {
            val repo = ctx.graph.repo
            repo.logDrink(DrinkType.WATER, 250, false)
            repo.logDrink(DrinkType.COFFEE, 150, false, caffeineMg = 90)
            repo.logDrink(DrinkType.LABAN, 330, true)
        }
        launch(com.waqtak.app.ui.Routes.HOME)
        DeviceUtils.screenshot("07_home")
        launch(com.waqtak.app.ui.Routes.SCHEDULE)
        DeviceUtils.screenshot("08_schedule")
        launch(com.waqtak.app.ui.Routes.DRINKS)
        DeviceUtils.screenshot("09_drinks")
        launch(com.waqtak.app.ui.Routes.HISTORY)
        DeviceUtils.screenshot("11_history")
        launch(com.waqtak.app.ui.Routes.SETTINGS)
        DeviceUtils.screenshot("12_settings")
        launch(com.waqtak.app.ui.Routes.READINESS)
        DeviceUtils.screenshot("13_readiness")
        launch(com.waqtak.app.ui.Routes.SCIENCE)
        DeviceUtils.screenshot("14_science")
        launch(com.waqtak.app.ui.Routes.HELP)
        DeviceUtils.screenshot("19_help")

        // a meal alarm on screen (test path), then start the meal timer from it
        runBlocking { ctx.graph.repo.startUnscheduledMeal(MealType.LUNCH, "", 25) }
        launch(com.waqtak.app.ui.Routes.TIMER)
        DeviceUtils.screenshot("15_timer")
        runBlocking { ctx.graph.repo.openSessions().forEach { ctx.graph.repo.finishSession(it.id, com.waqtak.app.domain.EndReason.USER_FINISHED) } }

        AlarmScheduler.scheduleTest(ctx, 2_000)
        DeviceUtils.waitRinging()
        // unlocked phone in use: Android shows a heads-up first; open the full alarm screen as a tap would
        RingingHub.alerts.value.firstOrNull()?.let { ctx.startActivity(com.waqtak.app.alarm.AlarmActivity.intent(ctx, it)) }
        d.wait(Until.findObject(By.text(s(R.string.alarm_test_title))), 10_000)
        DeviceUtils.screenshot("16_alarm_screen")
        RingingHub.alerts.value.forEach { a -> runBlocking { AlarmController.perform(ctx, a.id, UserAction.TEST_OK) } }

        // a drink reminder alarm (rings until answered), screenshot, then "close only"
        val drink = com.waqtak.app.domain.Alert("drink:shot", com.waqtak.app.domain.AlertKind.DRINK, null, "", System.currentTimeMillis(), "shot", 250)
        com.waqtak.app.alarm.AlarmService.ring(ctx, drink)
        DeviceUtils.waitRinging()
        ctx.startActivity(com.waqtak.app.alarm.AlarmActivity.intent(ctx, drink))
        d.wait(Until.findObject(By.text(s(R.string.action_close_only))), 10_000)
        DeviceUtils.screenshot("20_drink_alarm")
        runBlocking { AlarmController.perform(ctx, drink.id, UserAction.DRINK_CLOSE) }

        // English / LTR
        DeviceUtils.instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
        launch(com.waqtak.app.ui.Routes.HOME)
        DeviceUtils.screenshot("17_home_english")
        DeviceUtils.instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar")) }
    }
}
