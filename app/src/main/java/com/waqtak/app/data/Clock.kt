package com.waqtak.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.ZoneId

interface AppClock {
    fun now(): Long
    fun elapsed(): Long
    fun bootCount(): Int
    fun zone(): ZoneId
}

class SystemAppClock(private val context: Context) : AppClock {
    override fun now() = System.currentTimeMillis()
    override fun elapsed() = SystemClock.elapsedRealtime()
    override fun bootCount(): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
