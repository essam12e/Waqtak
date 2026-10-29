package com.waqtak.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavHostController
import com.waqtak.app.R
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.alarm.Notifications
import com.waqtak.app.alarm.TonePreview
import com.waqtak.app.data.AppSettings
import com.waqtak.app.data.Tone
import com.waqtak.app.data.backup.BackupCodec
import com.waqtak.app.data.backup.BackupException
import com.waqtak.app.domain.MealType
import com.waqtak.app.graph
import com.waqtak.app.ui.Routes
import com.waqtak.app.ui.components.ConfirmDialog
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.ui.components.Stepper
import com.waqtak.app.ui.components.SwitchRow
import com.waqtak.app.ui.components.TimePickerDialog
import com.waqtak.app.ui.components.TimeRow
import com.waqtak.app.ui.components.mealName
import com.waqtak.app.util.AppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun toneName(t: Tone) = stringResource(
    when (t) {
        Tone.PULSE -> R.string.tone_pulse
        Tone.CHIME -> R.string.tone_chime
        Tone.RISE -> R.string.tone_rise
    }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val settings by g.settings.flow.collectAsState(initial = null)
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Int?>(null) } // 1 = history, 2 = all
    var picker by remember { mutableStateOf<Pair<Int, Int>?>(null) } // which, minute
    DisposableEffect(Unit) { onDispose { TonePreview.stop() } }

    fun upd(block: (AppSettings) -> AppSettings) {
        scope.launch {
            g.settings.update(block)
            AlarmScheduler.rescheduleAll(ctx)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = try {
                val text = g.backup.export()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
                ctx.getString(R.string.export_done)
            } catch (e: Exception) {
                ctx.getString(R.string.export_failed)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = try {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = input.readNBytesCompat(BackupCodec.MAX_BYTES + 1)
                        if (bytes.size > BackupCodec.MAX_BYTES) throw BackupException(BackupException.Reason.TOO_LARGE, "too large")
                        String(bytes)
                    }
                }
                g.backup.import(text)
                com.waqtak.app.alarm.Reconciler.reconcile(ctx, "import")
                ctx.getString(R.string.import_done)
            } catch (e: BackupException) {
                ctx.getString(
                    when (e.reason) {
                        BackupException.Reason.NOT_JSON, BackupException.Reason.WRONG_FORMAT -> R.string.import_wrong_format
                        BackupException.Reason.NEWER_VERSION -> R.string.import_newer
                        BackupException.Reason.TOO_LARGE -> R.string.import_too_large
                        BackupException.Reason.INVALID_DATA -> R.string.import_invalid
                    }
                )
            } catch (e: Exception) {
                ctx.getString(R.string.import_wrong_format)
            }
        }
    }

    val s = settings ?: return
    ScreenColumn(stringResource(R.string.settings_title)) {
        message?.let {
            SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
                Text(it, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_got_it)) }
            }
        }
        SectionCard {
            SectionTitle(stringResource(R.string.language))
            val current = AppLocale.current().language
            listOf("ar" to R.string.lang_ar, "en" to R.string.lang_en).forEach { (tag, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(current == tag, role = Role.RadioButton) {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                        Notifications.ensureChannels(ctx)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == tag, onClick = null)
                    Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        SectionCard {
            SectionTitle(stringResource(R.string.settings_alarm))
            Text(stringResource(R.string.start_tone), style = MaterialTheme.typography.titleMedium)
            ToneChooser(s.startTone, { t -> upd { it.copy(startTone = t) } }) { TonePreview.play(ctx, it, s.alarmVolume, s.vibrate) }
            Text(stringResource(R.string.end_tone), style = MaterialTheme.typography.titleMedium)
            ToneChooser(s.endTone, { t -> upd { it.copy(endTone = t) } }) { TonePreview.play(ctx, it, s.alarmVolume, s.vibrate) }
            Text(stringResource(R.string.volume_percent, (s.alarmVolume * 100).toInt()), style = MaterialTheme.typography.titleMedium)
            val volDesc = stringResource(R.string.volume_percent, (s.alarmVolume * 100).toInt())
            var vol by remember(s.alarmVolume) { mutableStateOf(s.alarmVolume) }
            Slider(value = vol, onValueChange = { vol = it }, onValueChangeFinished = { upd { it.copy(alarmVolume = vol) } },
                valueRange = 0.2f..1f, modifier = Modifier.semantics { contentDescription = volDesc })
            NoteText(stringResource(R.string.volume_note))
            SwitchRow(stringResource(R.string.vibrate), s.vibrate, { v -> upd { it.copy(vibrate = v) } })
            SwitchRow(stringResource(R.string.crescendo), s.crescendo, { v -> upd { it.copy(crescendo = v) } }, stringResource(R.string.crescendo_desc))
            SecondaryButton(stringResource(R.string.action_stop_preview), { TonePreview.stop() })
            SecondaryButton(stringResource(R.string.nav_readiness), { nav.navigate(Routes.READINESS) })
        }

        SectionCard {
            SectionTitle(stringResource(R.string.settings_durations))
            NoteText(stringResource(R.string.durations_note))
            MealType.entries.forEach { t ->
                Stepper(mealName(t), s.duration(t), stringResource(R.string.unit_min), { v -> upd { it.copy(durations = it.durations + (t to v)) } }, 5..90)
            }
        }

        SectionCard {
            SectionTitle(stringResource(R.string.settings_hydration))
            SwitchRow(stringResource(R.string.hydration_enabled), s.hydrationEnabled, { v -> upd { it.copy(hydrationEnabled = v) } }, stringResource(R.string.hydration_enabled_desc))
            Stepper(stringResource(R.string.hydration_interval), s.hydrationIntervalMin, stringResource(R.string.unit_min), { v -> upd { it.copy(hydrationIntervalMin = v) } }, 30..240, 15)
            NoteText(stringResource(R.string.hydration_interval_note))
            Text(stringResource(R.string.default_amount), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp())) {
                listOf(150, 250, 330, 500).forEach { a ->
                    FilterChip(selected = s.defaultDrinkMl == a, onClick = { upd { it.copy(defaultDrinkMl = a) } }, label = { Text(stringResource(R.string.ml_value, a)) })
                }
            }
            var goal by remember(s.fluidGoalMl) { mutableStateOf(if (s.fluidGoalMl > 0) s.fluidGoalMl.toString() else "") }
            OutlinedTextField(
                goal, { v -> goal = v.filter(Char::isDigit).take(5); upd { it.copy(fluidGoalMl = goal.toIntOrNull()?.coerceIn(0, 10000) ?: 0) } },
                label = { Text(stringResource(if (s.hasFluidRestriction) R.string.health_fluid_goal else R.string.fluid_goal_optional)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            NoteText(stringResource(R.string.fluid_goal_note))
        }

        SectionCard {
            SectionTitle(stringResource(R.string.settings_caffeine))
            SwitchRow(stringResource(R.string.caffeine_reminder), s.caffeineReminderEnabled, { v -> upd { it.copy(caffeineReminderEnabled = v) } }, stringResource(R.string.caffeine_reminder_desc))
            Stepper(stringResource(R.string.caffeine_cutoff), s.caffeineCutoffHours, stringResource(R.string.unit_hours), { v -> upd { it.copy(caffeineCutoffHours = v) } }, 4..12, 1)
        }

        SectionCard {
            SectionTitle(stringResource(R.string.settings_window))
            SwitchRow(stringResource(R.string.eating_window), s.eatingWindowEnabled, { v -> upd { it.copy(eatingWindowEnabled = v) } }, stringResource(R.string.eating_window_desc))
            if (s.eatingWindowEnabled) {
                TimeRow(stringResource(R.string.window_start), s.eatingWindowStart) { picker = 1 to s.eatingWindowStart }
                TimeRow(stringResource(R.string.window_end), s.eatingWindowEnd) { picker = 2 to s.eatingWindowEnd }
            }
        }

        SectionCard {
            SectionTitle(stringResource(R.string.settings_health))
            SwitchRow(stringResource(R.string.health_reflux), s.hasReflux, { v -> upd { it.copy(hasReflux = v) } }, stringResource(R.string.health_reflux_desc))
            SwitchRow(stringResource(R.string.health_fluid), s.hasFluidRestriction, { v -> upd { it.copy(hasFluidRestriction = v) } }, stringResource(R.string.health_fluid_desc))
            SwitchRow(stringResource(R.string.health_medical), s.hasMedicalPlan, { v -> upd { it.copy(hasMedicalPlan = v) } }, stringResource(R.string.health_medical_desc))
        }

        SectionCard {
            SectionTitle(stringResource(R.string.settings_privacy))
            NoteText(stringResource(R.string.privacy_short))
            SecondaryButton(stringResource(R.string.nav_science), { nav.navigate(Routes.SCIENCE) })
            SecondaryButton(stringResource(R.string.action_export), { exportLauncher.launch("waqtak-backup-${LocalDate.now()}.json") })
            SecondaryButton(stringResource(R.string.action_import), { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            NoteText(stringResource(R.string.import_note))
            SecondaryButton(stringResource(R.string.action_delete_history), { confirm = 1 })
            SecondaryButton(stringResource(R.string.action_delete_all), { confirm = 2 })
        }
        NoteText(stringResource(R.string.app_version_line, com.waqtak.app.BuildConfigInfo.VERSION))
    }

    picker?.let { (which, minute) ->
        TimePickerDialog(stringResource(if (which == 1) R.string.window_start else R.string.window_end), minute, onDismiss = { picker = null }) { v ->
            picker = null
            upd { if (which == 1) it.copy(eatingWindowStart = v) else it.copy(eatingWindowEnd = v) }
        }
    }
    when (confirm) {
        1 -> ConfirmDialog(stringResource(R.string.delete_history_title), stringResource(R.string.delete_history_text), stringResource(R.string.action_delete),
            onDismiss = { confirm = null }) {
            confirm = null
            scope.launch { g.repo.deleteHistory(); AlarmScheduler.rescheduleAll(ctx); message = ctx.getString(R.string.deleted_done) }
        }
        2 -> ConfirmDialog(stringResource(R.string.delete_all_title), stringResource(R.string.delete_all_text), stringResource(R.string.action_delete),
            onDismiss = { confirm = null }) {
            confirm = null
            scope.launch {
                AlarmScheduler.cancelAll(ctx)
                Notifications.cancelTimer(ctx)
                g.repo.deleteAll()
                nav.navigate(Routes.ONBOARDING) { popUpTo(0) { inclusive = true } }
            }
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToneChooser(selected: Tone, onSelect: (Tone) -> Unit, onPreview: (Tone) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp())) {
        Tone.entries.forEach { t ->
            FilterChip(selected = selected == t, onClick = { onSelect(t); onPreview(t) }, label = { Text(toneName(t)) })
        }
    }
}
