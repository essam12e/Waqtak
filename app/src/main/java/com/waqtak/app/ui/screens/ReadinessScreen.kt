package com.waqtak.app.ui.screens

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.waqtak.app.R
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.alarm.Notifications
import com.waqtak.app.graph
import com.waqtak.app.ui.components.BigButton
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.ui.components.SwitchRow
import com.waqtak.app.util.TimeFmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Level { OK, ACTION, INFO }

data class ReadinessState(
    val notifications: Boolean,
    val alarmChannel: Boolean,
    val exact: Boolean,
    val fullScreen: Boolean,
    val battery: Boolean,
    val backgroundRestricted: Boolean,
    val alarmVolume: Int,
    val alarmVolumeMax: Int,
    val ringerMode: Int,
    val dndFilter: Int,
    val policyAccess: Boolean,
)

fun readReadiness(ctx: Context): ReadinessState {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val audio = ctx.getSystemService(AudioManager::class.java)
    val notifPerm = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val ch = nm.getNotificationChannel(Notifications.CH_RING)
    return ReadinessState(
        notifications = notifPerm && nm.areNotificationsEnabled(),
        alarmChannel = ch == null || ch.importance != NotificationManager.IMPORTANCE_NONE,
        exact = AlarmScheduler.canExact(ctx),
        fullScreen = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
        battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
        backgroundRestricted = ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        alarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
        alarmVolumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
        ringerMode = audio.ringerMode,
        dndFilter = nm.currentInterruptionFilter,
        policyAccess = nm.isNotificationPolicyAccessGranted,
    )
}

/** True when the essentials for a ringing alarm are in place. */
fun ReadinessState.essentialOk() = notifications && alarmChannel && exact && alarmVolume > 0

enum class Missing { NOTIFICATIONS, CHANNEL, EXACT, VOLUME }

fun ReadinessState.firstMissing(): Missing? = when {
    !notifications -> Missing.NOTIFICATIONS
    !alarmChannel -> Missing.CHANNEL
    !exact -> Missing.EXACT
    alarmVolume == 0 -> Missing.VOLUME
    else -> null
}

/** Opens the system screen that fixes [m]. */
fun openFix(ctx: Context, m: Missing) = open(
    ctx,
    when (m) {
        Missing.NOTIFICATIONS, Missing.CHANNEL -> appNotificationSettings(ctx)
        Missing.EXACT -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
        Missing.VOLUME -> Intent(Settings.ACTION_SOUND_SETTINGS)
    },
)

private fun open(ctx: Context, intent: Intent) {
    val i = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(i)
    } catch (e: Exception) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

fun appNotificationSettings(ctx: Context) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

@Composable
private fun StatusRow(level: Level, title: String, detail: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint, word) = when (level) {
                Level.OK -> Triple(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, stringResource(R.string.status_ok))
                Level.ACTION -> Triple(Icons.Default.WarningAmber, MaterialTheme.colorScheme.tertiary, stringResource(R.string.status_action))
                Level.INFO -> Triple(Icons.Default.Info, MaterialTheme.colorScheme.secondary, stringResource(R.string.status_info))
            }
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(8.dp))
            Text("$title — $word", style = MaterialTheme.typography.titleMedium)
        }
        NoteText(detail)
        if (actionLabel != null && onAction != null) SecondaryButton(actionLabel, onAction)
    }
}

/** Permission cards, requested one by one with an explanation; shared with onboarding. */
@Composable
fun PermissionCards(state: ReadinessState? = null, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; onChanged() }
    val st = state ?: remember(tick) { readReadiness(ctx) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++; onChanged() }

    StatusRow(
        if (st.notifications) Level.OK else Level.ACTION,
        stringResource(R.string.perm_notifications),
        stringResource(R.string.perm_notifications_desc),
        if (st.notifications) null else stringResource(R.string.action_allow),
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else open(ctx, appNotificationSettings(ctx))
    }
    StatusRow(
        if (st.exact) Level.OK else Level.ACTION,
        stringResource(R.string.perm_exact),
        stringResource(if (st.exact) R.string.perm_exact_desc else R.string.perm_exact_missing),
        if (st.exact || Build.VERSION.SDK_INT < 31) null else stringResource(R.string.action_allow),
    ) {
        open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
    }
    StatusRow(
        if (st.fullScreen) Level.OK else Level.ACTION,
        stringResource(R.string.perm_fullscreen),
        stringResource(if (st.fullScreen) R.string.perm_fullscreen_desc else R.string.perm_fullscreen_missing),
        if (st.fullScreen || Build.VERSION.SDK_INT < 34) null else stringResource(R.string.action_allow),
    ) {
        if (Build.VERSION.SDK_INT >= 34) open(ctx, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
    }
    StatusRow(
        if (st.battery) Level.OK else Level.INFO,
        stringResource(R.string.perm_battery),
        stringResource(R.string.perm_battery_desc),
        if (st.battery) null else stringResource(R.string.action_open_settings),
    ) { open(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

@Composable
fun ReadinessScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val st = remember(tick) { readReadiness(ctx) }
    val settings by ctx.graph.settings.flow.collectAsState(initial = null)
    var testAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nextMeal by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(tick) { nextMeal = AlarmScheduler.rescheduleAll(ctx).mealStart }
    LaunchedEffect(testAt) {
        while (testAt > 0 && now < testAt + 2_000) { delay(500); now = System.currentTimeMillis() }
    }

    ScreenColumn(stringResource(R.string.readiness_title)) {
        SectionCard(container = if (st.essentialOk()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer) {
            Text(
                stringResource(if (st.essentialOk()) R.string.readiness_summary_ok else R.string.readiness_summary_action),
                style = MaterialTheme.typography.titleLarge,
            )
            nextMeal?.let { NoteText(stringResource(R.string.readiness_next_alarm, TimeFmt.time(it), TimeFmt.date(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()))) }
        }
        SectionCard {
            Text(stringResource(R.string.test_title), style = MaterialTheme.typography.titleLarge)
            NoteText(stringResource(R.string.test_desc))
            val remaining = ((testAt - now) / 1000).coerceAtLeast(0)
            if (testAt > 0 && remaining > 0) {
                Text(stringResource(R.string.test_countdown, remaining), style = MaterialTheme.typography.headlineSmall)
                SecondaryButton(stringResource(R.string.action_cancel), { AlarmScheduler.cancelTest(ctx); testAt = 0 })
            } else {
                BigButton(stringResource(R.string.test_button), {
                    testAt = AlarmScheduler.scheduleTest(ctx, 15_000)
                    now = System.currentTimeMillis()
                })
            }
        }
        PermissionCards(st) { tick++ }
        StatusRow(
            if (st.alarmChannel) Level.OK else Level.ACTION,
            stringResource(R.string.perm_channel),
            stringResource(R.string.perm_channel_desc),
            if (st.alarmChannel) null else stringResource(R.string.action_open_settings),
        ) { open(ctx, appNotificationSettings(ctx)) }
        StatusRow(
            if (st.alarmVolume > 0) Level.OK else Level.ACTION,
            stringResource(R.string.alarm_volume_title),
            stringResource(if (st.alarmVolume > 0) R.string.alarm_volume_ok else R.string.alarm_volume_muted, st.alarmVolume, st.alarmVolumeMax),
            stringResource(R.string.action_open_sound),
        ) { open(ctx, Intent(Settings.ACTION_SOUND_SETTINGS)) }
        StatusRow(
            Level.INFO,
            stringResource(R.string.silent_title),
            stringResource(
                when (st.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> R.string.silent_on
                    AudioManager.RINGER_MODE_VIBRATE -> R.string.silent_vibrate
                    else -> R.string.silent_off
                }
            ),
        )
        SectionCard {
            Text(stringResource(R.string.dnd_title), style = MaterialTheme.typography.titleMedium)
            NoteText(
                stringResource(
                    if (st.dndFilter == NotificationManager.INTERRUPTION_FILTER_ALL || st.dndFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) R.string.dnd_off
                    else if (st.dndFilter == NotificationManager.INTERRUPTION_FILTER_NONE) R.string.dnd_total
                    else R.string.dnd_priority
                )
            )
            NoteText(stringResource(R.string.dnd_explain))
            val s = settings
            if (s != null) {
                SwitchRow(
                    stringResource(R.string.dnd_bypass),
                    s.bypassDnd && st.policyAccess,
                    { on ->
                        if (on && !st.policyAccess) {
                            open(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        }
                        scope.launch {
                            ctx.graph.settings.update { it.copy(bypassDnd = on) }
                            Notifications.ensureChannels(ctx)
                        }
                    },
                    stringResource(if (st.policyAccess) R.string.dnd_bypass_desc else R.string.dnd_bypass_needs_access),
                )
            }
        }
        if (st.backgroundRestricted) {
            StatusRow(Level.ACTION, stringResource(R.string.bg_restricted_title), stringResource(R.string.bg_restricted_desc), stringResource(R.string.action_open_settings)) {
                open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        SectionCard {
            Text(stringResource(R.string.tips_battery_title), style = MaterialTheme.typography.titleMedium)
            NoteText(stringResource(R.string.tips_battery))
        }
        SectionCard {
            Text(stringResource(R.string.limits_title), style = MaterialTheme.typography.titleMedium)
            NoteText(stringResource(R.string.limits_text))
        }
    }
}
