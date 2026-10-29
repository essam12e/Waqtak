package com.waqtak.app

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.alarm.RingingHub
import java.io.File
import java.io.FileInputStream

object DeviceUtils {
    val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = instrumentation.targetContext.applicationContext
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    val pkg: String get() = ctx.packageName

    fun shell(cmd: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    fun grantAlarmPermissions() {
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        shell("appops set $pkg SCHEDULE_EXACT_ALARM allow")
        shell("appops set $pkg USE_FULL_SCREEN_INTENT allow")
        shell("cmd notification allow_dnd $pkg")
    }

    fun waitUntil(timeoutMs: Long, step: Long = 200, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return true
            SystemClock.sleep(step)
        }
        return cond()
    }

    fun waitRinging(timeoutMs: Long = 30_000) = waitUntil(timeoutMs) { RingingHub.alerts.value.isNotEmpty() }

    fun screenshot(name: String) {
        SystemClock.sleep(700)
        val dir = File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    fun log(name: String, text: String) {
        val dir = File(ctx.getExternalFilesDir(null), "reports").apply { mkdirs() }
        File(dir, "$name.txt").appendText(text + "\n")
    }

    fun wakeAndUnlock() {
        device.wakeUp()
        shell("wm dismiss-keyguard")
        SystemClock.sleep(500)
    }

    fun cancelTest() = AlarmScheduler.cancelTest(ctx)

    @Suppress("unused")
    fun readFile(f: File) = FileInputStream(f).use { String(it.readBytes()) }
}
