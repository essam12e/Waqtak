package com.waqtak.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.waqtak.app.MainActivity
import com.waqtak.app.R
import com.waqtak.app.domain.Alert
import com.waqtak.app.domain.AlertKind
import com.waqtak.app.domain.MealType
import com.waqtak.app.util.AppLocale
import com.waqtak.app.util.TimeFmt

object Notifications {
    const val CH_RING = "alarm_ring_v1"
    const val CH_RING_DND = "alarm_ring_dnd_v1"
    const val CH_FALLBACK = "alarm_fallback_v1"
    const val CH_HYDRATION = "hydration_v1"
    const val CH_TIMER = "timer_v1"
    const val CH_INFO = "info_v1"

    const val ID_RINGING = 1000
    const val ID_TIMER = 3000
    const val ID_DRINK = 4000
    const val ID_CAFFEINE = 4100
    const val TAG_FALLBACK = "fallback"

    fun ensureChannels(context: Context) {
        val ctx = AppLocale.wrap(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val ring = NotificationChannel(CH_RING, ctx.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.channel_alarm_desc)
            setSound(null, null) // the foreground service plays the sound itself on the alarm stream
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val fallback = NotificationChannel(CH_FALLBACK, ctx.getString(R.string.channel_fallback), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.channel_fallback_desc)
            setSound(
                Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.tone_pulse}"),
                AlarmPlayer.ALARM_ATTRIBUTES,
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 500, 700)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val hydration = NotificationChannel(CH_HYDRATION, ctx.getString(R.string.channel_hydration), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.channel_hydration_desc)
        }
        val timer = NotificationChannel(CH_TIMER, ctx.getString(R.string.channel_timer), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.channel_timer_desc)
            setSound(null, null)
            enableVibration(false)
        }
        val info = NotificationChannel(CH_INFO, ctx.getString(R.string.channel_info), NotificationManager.IMPORTANCE_DEFAULT)
        val list = mutableListOf(ring, fallback, hydration, timer, info)
        if (nm.isNotificationPolicyAccessGranted) {
            list += NotificationChannel(CH_RING_DND, ctx.getString(R.string.channel_alarm_dnd), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_alarm_desc)
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        }
        nm.createNotificationChannels(list)
    }

    fun ringChannel(context: Context, bypassDnd: Boolean): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        return if (bypassDnd && nm.isNotificationPolicyAccessGranted && nm.getNotificationChannel(CH_RING_DND) != null) CH_RING_DND else CH_RING
    }

    fun mealName(ctx: Context, type: MealType?): String = when (type) {
        MealType.BREAKFAST -> ctx.getString(R.string.meal_breakfast)
        MealType.LUNCH -> ctx.getString(R.string.meal_lunch)
        MealType.DINNER -> ctx.getString(R.string.meal_dinner)
        MealType.SNACK -> ctx.getString(R.string.meal_snack)
        null -> ctx.getString(R.string.meal_generic)
    }

    fun alertTitle(ctx: Context, a: Alert): String = when (a.kind) {
        AlertKind.MEAL_START -> a.label.ifBlank { mealName(ctx, a.mealType) }
        AlertKind.MEAL_END -> ctx.getString(R.string.alarm_end_title)
        AlertKind.TEST -> ctx.getString(R.string.alarm_test_title)
        AlertKind.DRINK -> ctx.getString(R.string.drink_reminder_title)
    }

    fun alertText(ctx: Context, a: Alert): String = when (a.kind) {
        AlertKind.MEAL_START -> ctx.getString(R.string.alarm_start_text, TimeFmt.time(a.scheduledAt))
        AlertKind.MEAL_END -> ctx.getString(R.string.alarm_end_text)
        AlertKind.TEST -> ctx.getString(R.string.alarm_test_text)
        AlertKind.DRINK -> ctx.getString(R.string.drink_reminder_text)
    }

    private fun actionPi(ctx: Context, alertId: String, action: String, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, rc,
            Intent(ctx, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_ALERT, alertId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun alarmActivityPi(ctx: Context, a: Alert): PendingIntent = PendingIntent.getActivity(
        ctx, 7000 + (a.id.hashCode() and 0xFFF),
        AlarmActivity.intent(ctx, a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ringing(context: Context, a: Alert, channel: String, others: Int): Notification {
        val ctx = AppLocale.wrap(context)
        val full = alarmActivityPi(context, a)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_waqtak)
            .setContentTitle(alertTitle(ctx, a))
            .setContentText(alertText(ctx, a))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (others > 0) b.setSubText(ctx.getString(R.string.alarm_more_waiting, others))
        addActions(ctx, context, b, a)
        return b.build()
    }

    private fun addActions(ctx: Context, base: Context, b: NotificationCompat.Builder, a: Alert) {
        val h = a.id.hashCode() and 0xFFF
        when (a.kind) {
            AlertKind.MEAL_START -> {
                b.addAction(0, ctx.getString(R.string.action_start_eating), actionPi(base, a.id, NotificationActionReceiver.ACTION_START, 10000 + h))
                b.addAction(0, ctx.getString(R.string.action_snooze_10), actionPi(base, a.id, NotificationActionReceiver.ACTION_SNOOZE, 20000 + h))
                b.addAction(0, ctx.getString(R.string.action_skip_meal), actionPi(base, a.id, NotificationActionReceiver.ACTION_SKIP, 30000 + h))
            }
            AlertKind.MEAL_END -> {
                b.addAction(0, ctx.getString(R.string.action_ok), actionPi(base, a.id, NotificationActionReceiver.ACTION_END_OK, 40000 + h))
                b.addAction(0, ctx.getString(R.string.action_need_5_more), actionPi(base, a.id, NotificationActionReceiver.ACTION_END_EXTEND, 50000 + h))
            }
            AlertKind.TEST -> b.addAction(0, ctx.getString(R.string.action_ok), actionPi(base, a.id, NotificationActionReceiver.ACTION_TEST_OK, 60000 + h))
            AlertKind.DRINK -> {
                val amount = a.durationMin.takeIf { it > 0 } ?: 250 // drink alerts carry the amount here
                b.addAction(0, ctx.getString(R.string.action_drank_amount, amount), actionPi(base, a.id, NotificationActionReceiver.ACTION_ALARM_DRANK, 61000 + h))
                b.addAction(0, ctx.getString(R.string.action_close_only), actionPi(base, a.id, NotificationActionReceiver.ACTION_ALARM_DRINK_CLOSE, 62000 + h))
                b.addAction(0, ctx.getString(R.string.action_snooze_15), actionPi(base, a.id, NotificationActionReceiver.ACTION_ALARM_DRINK_SNOOZE, 63000 + h))
            }
        }
    }

    /** Used only if the ringing service cannot be started: the channel itself plays an insistent alarm sound. */
    fun showFallbackAlarm(context: Context, a: Alert) {
        val ctx = AppLocale.wrap(context)
        val full = alarmActivityPi(context, a)
        val b = NotificationCompat.Builder(context, CH_FALLBACK)
            .setSmallIcon(R.drawable.ic_stat_waqtak)
            .setContentTitle(alertTitle(ctx, a))
            .setContentText(alertText(ctx, a))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
        addActions(ctx, context, b, a)
        val n = b.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        runCatching { context.getSystemService(NotificationManager::class.java).notify(TAG_FALLBACK, a.id.hashCode(), n) }
    }

    fun cancelFallback(context: Context, alertId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(TAG_FALLBACK, alertId.hashCode())
    }

    fun activeFallbackHashes(context: Context): Set<Int> = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag == TAG_FALLBACK }.map { it.id }.toSet()
    }.getOrDefault(emptySet())

    fun openAppPi(ctx: Context, route: String?, rc: Int): PendingIntent = PendingIntent.getActivity(
        ctx, rc,
        Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showDrinkReminder(context: Context, token: String, amountMl: Int) {
        val ctx = AppLocale.wrap(context)
        fun pi(action: String, rc: Int) = PendingIntent.getBroadcast(
            context, rc,
            Intent(context, NotificationActionReceiver::class.java).setAction(action)
                .putExtra(NotificationActionReceiver.EXTRA_TOKEN, token)
                .putExtra(NotificationActionReceiver.EXTRA_AMOUNT, amountMl),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_HYDRATION)
            .setSmallIcon(R.drawable.ic_stat_waqtak)
            .setContentTitle(ctx.getString(R.string.drink_reminder_title))
            .setContentText(ctx.getString(R.string.drink_reminder_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.drink_reminder_text)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppPi(context, "drinks", 4001))
            .addAction(0, ctx.getString(R.string.action_drank_amount, amountMl), pi(NotificationActionReceiver.ACTION_DRANK, 4002))
            .addAction(0, ctx.getString(R.string.action_snooze), pi(NotificationActionReceiver.ACTION_DRINK_SNOOZE, 4003))
            .addAction(0, ctx.getString(R.string.action_close_only), pi(NotificationActionReceiver.ACTION_DRINK_SKIP, 4004))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_DRINK, n) }
    }

    fun cancelDrink(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_DRINK)

    fun showCaffeineNote(context: Context) {
        val ctx = AppLocale.wrap(context)
        val n = NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_waqtak)
            .setContentTitle(ctx.getString(R.string.caffeine_reminder_title))
            .setContentText(ctx.getString(R.string.caffeine_reminder_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.caffeine_reminder_text)))
            .setAutoCancel(true)
            .setContentIntent(openAppPi(context, "drinks", 4101))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_CAFFEINE, n) }
    }

    fun showTimer(context: Context, sessionId: Long, title: String, endWall: Long) {
        val ctx = AppLocale.wrap(context)
        val finish = PendingIntent.getBroadcast(
            context, 3001,
            Intent(context, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_FINISH_MEAL)
                .putExtra(NotificationActionReceiver.EXTRA_SESSION, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_waqtak)
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.timer_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(endWall)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openAppPi(context, "timer", 3002))
            .addAction(0, ctx.getString(R.string.action_finished_meal), finish)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_TIMER, n) }
    }

    fun cancelTimer(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_TIMER)

    /** Used when the user switched the end-of-meal alarm off. */
    fun showMealEndNote(context: Context) {
        val ctx = AppLocale.wrap(context)
        val n = NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_waqtak)
            .setContentTitle(ctx.getString(R.string.alarm_end_title))
            .setContentText(ctx.getString(R.string.alarm_end_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.alarm_end_text)))
            .setAutoCancel(true)
            .setContentIntent(openAppPi(context, null, 3003))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID_TIMER + 1, n) }
    }
}
