package com.waqtak.app.alarm

import android.content.Context
import android.content.Intent
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Brings state back in line after anything that can invalidate alarms: reboot, app update,
 * clock or zone change, exact-alarm permission change, force-stop (on next app open).
 * Policy: never replay a backlog of old alarms. Old meals become a "missed" summary; a meal
 * timer that ended within the last 15 minutes rings once; older ones close silently.
 */
object Reconciler {
    private val mutex = Mutex()

    suspend fun reconcile(context: Context, reason: String) = mutex.withLock {
        val ctx = context.applicationContext
        val g = ctx.graph
        val repo = g.repo
        val now = repo.clock.now()
        if (!g.settings.get().onboardingDone) {
            AlarmScheduler.cancelAll(ctx)
            return@withLock
        }
        val alive = withContext(Dispatchers.Main.immediate) { AlarmService.instance?.activeAlertIds() ?: emptySet() }
        val fallback = Notifications.activeFallbackHashes(ctx)
        fun isAlive(id: String) = id in alive || id.hashCode() in fallback
        val afterBoot = reason == Intent.ACTION_BOOT_COMPLETED

        for (a in repo.ringingMealAlerts()) {
            if (isAlive(a.id)) continue
            if (!afterBoot && now - a.scheduledAt <= MealDefaults.END_GRACE_MS) {
                repo.requeueRinging(a.ref, now + 2_000) // rings once more via the normal path
            } else {
                repo.abandonRinging(a.ref, now)
            }
        }
        val endAlive = repo.ringingEndAlerts().filter { isAlive(it.id) }.mapNotNull { it.ref.toLongOrNull() }.toSet()
        repo.reconcileSessions(now, endAlive)
        repo.markMissed(now)
        g.settings.update { it.copy(lastReconcileAt = now) }
        AlarmScheduler.rescheduleAll(ctx)
        TimerSync.sync(ctx)
    }
}
