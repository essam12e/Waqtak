package com.waqtak.app

import android.app.Application
import android.content.Context
import com.waqtak.app.alarm.Notifications
import com.waqtak.app.data.AppClock
import com.waqtak.app.data.SettingsRepository
import com.waqtak.app.data.SystemAppClock
import com.waqtak.app.data.WaqtakRepository
import com.waqtak.app.data.backup.BackupManager
import com.waqtak.app.data.db.WaqtakDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency graph: everything is local, no network, no DI framework needed. */
class AppGraph(context: Context, clock: AppClock = SystemAppClock(context), inMemoryDb: Boolean = false) {
    val db: WaqtakDatabase = WaqtakDatabase.build(context, inMemoryDb)
    val settings = SettingsRepository(context)
    val repo = WaqtakRepository(db, settings, clock)
    val backup = BackupManager(repo)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

class WaqtakApp : Application() {
    lateinit var graph: AppGraph
        internal set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.ensureChannels(this)
    }
}

val Context.graph: AppGraph get() = (applicationContext as WaqtakApp).graph
