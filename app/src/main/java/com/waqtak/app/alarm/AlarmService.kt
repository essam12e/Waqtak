package com.waqtak.app.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.waqtak.app.data.AppSettings
import com.waqtak.app.domain.Alert
import com.waqtak.app.domain.AlertKind
import com.waqtak.app.domain.AlertQueue
import com.waqtak.app.domain.MealType
import com.waqtak.app.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that exists only while something is ringing. It plays one alert at a
 * time from a priority queue (meal end > meal start > test), so simultaneous events never
 * overlap sounds and duplicate deliveries are ignored.
 */
class AlarmService : Service() {
    companion object {
        private const val TAG = "WaqtakAlarmService"
        const val ACTION_RING = "com.waqtak.app.alarm.RING"
        private const val EXTRA_ID = "id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_MEAL = "meal"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_AT = "at"
        private const val EXTRA_REF = "ref"
        private const val EXTRA_DURATION = "duration"

        @Volatile
        var instance: AlarmService? = null
            private set

        fun putAlert(i: Intent, a: Alert): Intent = i.putExtra(EXTRA_ID, a.id).putExtra(EXTRA_KIND, a.kind.name)
            .putExtra(EXTRA_MEAL, a.mealType?.name).putExtra(EXTRA_LABEL, a.label).putExtra(EXTRA_AT, a.scheduledAt)
            .putExtra(EXTRA_REF, a.ref).putExtra(EXTRA_DURATION, a.durationMin)

        fun readAlert(i: Intent?): Alert? {
            val id = i?.getStringExtra(EXTRA_ID) ?: return null
            val kind = AlertKind.entries.firstOrNull { it.name == i.getStringExtra(EXTRA_KIND) } ?: return null
            return Alert(
                id = id, kind = kind, mealType = i.getStringExtra(EXTRA_MEAL)?.let { MealType.parse(it) },
                label = i.getStringExtra(EXTRA_LABEL) ?: "", scheduledAt = i.getLongExtra(EXTRA_AT, 0),
                ref = i.getStringExtra(EXTRA_REF) ?: "", durationMin = i.getIntExtra(EXTRA_DURATION, 0),
            )
        }

        /** Starts ringing; falls back to an insistent alarm notification if the service is not allowed to start. */
        fun ring(ctx: Context, a: Alert) {
            try {
                ContextCompat.startForegroundService(ctx, putAlert(Intent(ctx, AlarmService::class.java).setAction(ACTION_RING), a))
            } catch (e: Exception) {
                Log.w(TAG, "foreground service start refused; using notification fallback", e)
                Notifications.showFallbackAlarm(ctx, a)
            }
        }
    }

    private val queue = AlertQueue()
    private lateinit var player: AlarmPlayer
    private var playingId: String? = null
    private var foreground = false
    private var settings: AppSettings? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        player = AlarmPlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alert = readAlert(intent)
        if (alert != null) {
            queue.add(alert)
            if (!goForeground(alert)) return START_NOT_STICKY
            loadSettingsThenRefresh()
        } else {
            // Restarted by the system after the process was killed while ringing: restore from the database.
            val placeholder = Alert("restore", AlertKind.TEST, null, "", System.currentTimeMillis(), "")
            if (!goForeground(placeholder)) return START_NOT_STICKY
            scope.launch {
                val repo = graph.repo
                (repo.ringingEndAlerts() + repo.ringingMealAlerts()).forEach { queue.add(it) }
                settings = graph.settings.get()
                refresh()
            }
        }
        return START_STICKY
    }

    private fun goForeground(a: Alert): Boolean {
        val channel = Notifications.ringChannel(this, settings?.bypassDnd == true)
        val n = Notifications.ringing(this, a, channel, (queue.all.size - 1).coerceAtLeast(0))
        val types = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && AlarmScheduler.canExact(this)) {
            types += ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        }
        types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        for (type in types) {
            try {
                ServiceCompat.startForeground(this, Notifications.ID_RINGING, n, type)
                foreground = true
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground type=$type failed", e)
            }
        }
        // Cannot be foreground: hand the alerts to the notification fallback and stop.
        queue.all.filter { it.id != "restore" }.forEach { Notifications.showFallbackAlarm(this, it) }
        queue.clear()
        stopEverything()
        return false
    }

    private fun loadSettingsThenRefresh() {
        scope.launch {
            settings = graph.settings.get()
            refresh()
        }
    }

    private fun refresh() {
        val top = queue.top
        RingingHub.publish(queue.all)
        if (top == null) {
            stopEverything()
            return
        }
        goForeground(top)
        if (playingId != top.id) {
            player.stop()
            val s = settings ?: AppSettings()
            val tone = if (top.kind == AlertKind.MEAL_END) s.endTone else s.startTone
            player.start(tone, s.alarmVolume, s.crescendo, s.vibrate)
            playingId = top.id
        }
    }

    /** id of the alert whose sound/vibration is currently on, or null when silent. */
    val playingAlertId: String? get() = playingId

    fun activeAlertIds(): Set<String> = queue.all.map { it.id }.toSet()

    /** Immediate silence, called on the main thread from the alarm screen or notification action. */
    fun silence(alertId: String) {
        if (playingId == alertId || alertId.isEmpty()) {
            player.stop()
            playingId = null
        }
    }

    /** The alert was handled; play the next one, or stop the service. */
    fun resolve(alertId: String) {
        silence(alertId)
        if (queue.remove(alertId) == null && !queue.isEmpty()) return
        refresh()
    }

    private fun stopEverything() {
        player.stop()
        playingId = null
        RingingHub.publish(emptyList())
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        player.stop()
        RingingHub.publish(emptyList())
        instance = null
        scope.cancel()
        super.onDestroy()
    }
}
