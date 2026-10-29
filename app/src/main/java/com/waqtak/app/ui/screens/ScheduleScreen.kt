package com.waqtak.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.waqtak.app.R
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.data.AppSettings
import com.waqtak.app.data.PlanDraft
import com.waqtak.app.data.ScheduleSnapshot
import com.waqtak.app.data.TimeChange
import com.waqtak.app.data.db.MealPlanEntity
import com.waqtak.app.domain.DrinkReminderStyle
import com.waqtak.app.domain.DrinkScheduleMode
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.ScheduleEngine
import com.waqtak.app.domain.ScheduleMode
import com.waqtak.app.graph
import com.waqtak.app.ui.art.FlatArt
import com.waqtak.app.ui.art.art
import com.waqtak.app.ui.components.ConfirmDialog
import com.waqtak.app.ui.components.DaysChips
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.ui.components.Stepper
import com.waqtak.app.ui.components.SwitchRow
import com.waqtak.app.ui.components.TimePickerDialog
import com.waqtak.app.ui.components.TimeRow
import com.waqtak.app.ui.components.mealName
import com.waqtak.app.util.AppLocale
import com.waqtak.app.util.TimeFmt
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle

private data class Picker(val title: String, val minute: Int, val onPick: (Int) -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val snap by g.repo.scheduleFlow().collectAsState(initial = null)
    val settings by g.settings.flow.collectAsState(initial = null)
    var selected by rememberSaveable { mutableLongStateOf(-1L) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var deletePlan by remember { mutableStateOf<MealPlanEntity?>(null) }
    var autoEdit by remember { mutableStateOf<MealPlanEntity?>(null) }
    var changes by remember { mutableStateOf<List<TimeChange>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    fun save(block: suspend () -> Unit) {
        scope.launch {
            block()
            AlarmScheduler.rescheduleAll(ctx) // replaces the old alarms; never duplicates
        }
    }

    val sn = snap ?: return
    val s = settings ?: return
    val auto = s.scheduleMode == ScheduleMode.AUTO
    val profile = sn.profiles.firstOrNull { it.id == selected } ?: sn.profiles.firstOrNull()

    ScreenColumn(stringResource(R.string.schedule_title)) {
        ModeCard(s)
        NoteText(stringResource(R.string.schedule_note))
        message?.let {
            SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
                Text(it, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_got_it)) }
            }
        }
        if (s.hasMedicalPlan) {
            SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer) { NoteText(stringResource(R.string.medical_plan_note)) }
        }
        if (sn.profiles.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sn.profiles.forEach { p ->
                    FilterChip(selected = p.id == profile?.id, onClick = { selected = p.id }, label = { Text(p.name) })
                }
            }
        }
        if (profile == null) {
            NoteText(stringResource(R.string.schedule_empty))
            SecondaryButton(stringResource(R.string.restore_default), { confirmRestore = true })
            return@ScreenColumn
        }
        val plans = sn.plans.filter { it.profileId == profile.id }
            .sortedBy { ScheduleEngine.relative(profile.wakeMinute, it.minuteOfDay) }

        if (auto) {
            SectionCard {
                TimeRow(stringResource(R.string.wake_time), profile.wakeMinute) {
                    picker = Picker(ctx.getString(R.string.wake_time), profile.wakeMinute) { v ->
                        scope.launch {
                            g.repo.updateProfile(profile.copy(wakeMinute = v))
                            g.settings.update { it.copy(autoWake = v) }
                            changes = g.repo.recomputeAuto(s.hasReflux)
                            AlarmScheduler.rescheduleAll(ctx)
                        }
                    }
                }
                TimeRow(stringResource(R.string.sleep_time), profile.sleepMinute) {
                    picker = Picker(ctx.getString(R.string.sleep_time), profile.sleepMinute) { v ->
                        scope.launch {
                            g.repo.updateProfile(profile.copy(sleepMinute = v))
                            g.settings.update { it.copy(autoSleep = v) }
                            changes = g.repo.recomputeAuto(s.hasReflux)
                            AlarmScheduler.rescheduleAll(ctx)
                        }
                    }
                }
                NoteText(stringResource(R.string.auto_recompute_note))
            }
        }

        val issues = if (auto) ScheduleEngine.review(
            profile.wakeMinute, profile.sleepMinute,
            plans.filter { it.enabled }.map { ScheduleEngine.MealSlot(it.id, it.minuteOfDay, it.durationMin) }, s.hasReflux,
        ) else emptyMap()

        plans.forEach { p ->
            val type = MealType.parse(p.type)
            val name = p.label.ifBlank { mealName(type) }
            SectionCard(container = if (p.enabled) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatArt(type.art(), Modifier.size(52.dp))
                    Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Switch(checked = p.enabled, onCheckedChange = { on -> save { g.repo.updatePlan(p.copy(enabled = on), markEdited = false) } })
                    if (!auto) {
                        val del = stringResource(R.string.cd_delete, name)
                        IconButton(onClick = { deletePlan = p }, modifier = Modifier.semantics { contentDescription = del }) { Icon(Icons.Default.Delete, null) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.meal_time), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        if (auto) autoEdit = p
                        else picker = Picker(name, p.minuteOfDay) { v -> save { g.repo.updatePlan(p.copy(minuteOfDay = v)) } }
                    }) { Text(TimeFmt.clock(p.minuteOfDay), style = MaterialTheme.typography.titleLarge) }
                }
                Stepper(stringResource(R.string.meal_duration), p.durationMin, stringResource(R.string.unit_min), { v ->
                    save { g.repo.updatePlan(p.copy(durationMin = v), markEdited = false) }
                }, MealDefaults.MIN_DURATION..MealDefaults.MAX_DURATION)
                Text(stringResource(R.string.repeat_days), style = MaterialTheme.typography.titleSmall)
                DaysChips(p.daysMask) { mask -> save { g.repo.setPlanDays(p, mask) } }
                issues[p.id]?.forEach { issue ->
                    NoteText(
                        "• " + stringResource(
                            when (issue) {
                                ScheduleEngine.Issue.OUTSIDE_WAKING_HOURS -> R.string.issue_outside
                                ScheduleEngine.Issue.CLOSE_TO_SLEEP -> if (s.hasReflux) R.string.issue_close_sleep_reflux else R.string.issue_close_sleep
                                ScheduleEngine.Issue.VERY_CLOSE_MEALS -> R.string.issue_close_meals
                            }
                        )
                    )
                }
            }
        }

        SectionCard {
            Text(stringResource(R.string.add_meal), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealType.entries.forEach { t ->
                    FilterChip(selected = false, onClick = {
                        save {
                            g.repo.addPlan(profile.id, PlanDraft(t, "", defaultMinuteFor(t, sn, profile.wakeMinute, profile.sleepMinute, auto, s), s.duration(t)), userEdited = false)
                            if (auto) changes = g.repo.recomputeAuto(s.hasReflux)
                        }
                    }, label = { Text("+ " + mealName(t)) })
                }
            }
        }

        DrinkScheduleCard(s, auto, onPick = { picker = it }) { block -> save(block) }

        SectionCard {
            SwitchRow(
                stringResource(R.string.end_alarm_enabled), s.endAlarmEnabled,
                { v -> save { g.settings.update { it.copy(endAlarmEnabled = v) } } },
                stringResource(R.string.end_alarm_desc),
            )
        }

        if (sn.profiles.size > 1) WeekdayAssignment(sn) { block -> save(block) }

        SecondaryButton(stringResource(R.string.restore_default), { confirmRestore = true })
    }

    picker?.let { p ->
        TimePickerDialog(p.title, p.minute, onDismiss = { picker = null }, onConfirm = { picker = null; p.onPick(it) })
    }
    deletePlan?.let { p ->
        ConfirmDialog(stringResource(R.string.delete_meal_title), stringResource(R.string.delete_meal_text), stringResource(R.string.action_delete),
            onDismiss = { deletePlan = null }, onConfirm = { deletePlan = null; save { g.repo.deletePlan(p.id) } })
    }
    autoEdit?.let { p ->
        AutoEditDialog(
            p, onDismiss = { autoEdit = null },
            onThisDay = {
                autoEdit = null
                picker = Picker(ctx.getString(R.string.edit_this_day), p.minuteOfDay) { v ->
                    scope.launch {
                        val now = g.repo.clock.now()
                        val today = g.repo.occurrences(g.repo.schedule(), now - 2 * 3_600_000L, now + 30 * 3_600_000L)
                            .firstOrNull { it.planId == p.id && it.atMillis >= now - 2 * 3_600_000L }
                        message = if (today == null) ctx.getString(R.string.edit_this_day_none) else {
                            val at = g.repo.setTimeForThisDay(today, v)
                            if (at != null && at < now) ctx.getString(R.string.this_day_past) else ctx.getString(R.string.this_day_set)
                        }
                        AlarmScheduler.rescheduleAll(ctx)
                    }
                }
            },
            onSwitchManual = {
                autoEdit = null
                picker = Picker(mealName(MealType.parse(p.type)), p.minuteOfDay) { v ->
                    save {
                        g.settings.update { it.copy(scheduleMode = ScheduleMode.MANUAL) }
                        g.repo.updatePlan(p.copy(minuteOfDay = v))
                    }
                }
            },
        )
    }
    changes?.let { list ->
        AlertDialog(
            onDismissRequest = { changes = null },
            title = { Text(stringResource(R.string.auto_updated_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (list.isEmpty()) NoteText(stringResource(R.string.auto_updated_none))
                    list.forEach { c ->
                        Text(stringResource(R.string.change_line, mealName(c.type), TimeFmt.clock(c.oldMinute), TimeFmt.clock(c.newMinute)), style = MaterialTheme.typography.titleSmall)
                    }
                    NoteText(stringResource(R.string.auto_updated_note))
                }
            },
            confirmButton = { TextButton(onClick = { changes = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
    if (confirmRestore) {
        ConfirmDialog(stringResource(R.string.restore_default_title), stringResource(R.string.restore_default_text), stringResource(R.string.action_restore),
            onDismiss = { confirmRestore = false }) {
            confirmRestore = false
            save {
                g.repo.applyDefaultSchedule(ctx.getString(R.string.profile_everyday))
                message = ctx.getString(R.string.restore_done)
            }
        }
    }
}

private fun defaultMinuteFor(t: MealType, sn: ScheduleSnapshot, wake: Int, sleep: Int, auto: Boolean, s: AppSettings): Int {
    if (auto) {
        val types = sn.plans.filter { it.enabled }.map { MealType.parse(it.type) } + t
        ScheduleEngine.propose(wake, sleep, types, s.hasReflux).meals.firstOrNull { it.type == t }?.let { return it.minuteOfDay }
    }
    return com.waqtak.app.domain.DefaultSchedule.meals.firstOrNull { it.type == t }?.minuteOfDay ?: (16 * 60 + 30)
}

/** Current scheduling mode with a reviewed switch to the other one. Shared with Settings. */
@Composable
fun ModeCard(s: AppSettings) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    var toAuto by remember { mutableStateOf(false) }
    var toManual by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<List<TimeChange>?>(null) }
    val auto = s.scheduleMode == ScheduleMode.AUTO
    SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Text(stringResource(R.string.schedule_mode_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (auto) R.string.mode_auto else R.string.mode_manual), style = MaterialTheme.typography.titleLarge)
        NoteText(stringResource(if (auto) R.string.mode_auto_desc else R.string.mode_manual_desc))
        SecondaryButton(
            stringResource(if (auto) R.string.action_switch_to_manual else R.string.action_switch_to_auto),
            { if (auto) toManual = true else toAuto = true },
        )
    }
    if (toManual) {
        ConfirmDialog(stringResource(R.string.action_switch_to_manual), stringResource(R.string.switch_manual_text), stringResource(R.string.action_ok),
            onDismiss = { toManual = false }) {
            toManual = false
            scope.launch {
                g.settings.update { it.copy(scheduleMode = ScheduleMode.MANUAL) }
                AlarmScheduler.rescheduleAll(ctx)
            }
        }
    }
    if (toAuto) {
        SwitchToAutoDialog(s, onDismiss = { toAuto = false }) { wake, sleep ->
            toAuto = false
            scope.launch {
                g.repo.schedule().profiles.forEach { g.repo.updateProfile(it.copy(wakeMinute = wake, sleepMinute = sleep)) }
                g.settings.update { it.copy(scheduleMode = ScheduleMode.AUTO, autoWake = wake, autoSleep = sleep) }
                summary = g.repo.recomputeAuto(s.hasReflux)
                AlarmScheduler.rescheduleAll(ctx)
            }
        }
    }
    summary?.let { list ->
        AlertDialog(
            onDismissRequest = { summary = null },
            title = { Text(stringResource(R.string.auto_updated_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (list.isEmpty()) NoteText(stringResource(R.string.auto_updated_none))
                    list.forEach { c ->
                        Text(stringResource(R.string.change_line, mealName(c.type), TimeFmt.clock(c.oldMinute), TimeFmt.clock(c.newMinute)), style = MaterialTheme.typography.titleSmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { summary = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

/** Preview of the automatic schedule before switching to it. */
@Composable
private fun SwitchToAutoDialog(s: AppSettings, onDismiss: () -> Unit, onApply: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val snap by ctx.graph.repo.scheduleFlow().collectAsState(initial = null)
    var wake by remember { mutableIntStateOf(s.autoWake) }
    var sleep by remember { mutableIntStateOf(s.autoSleep) }
    var pick by remember { mutableIntStateOf(0) }
    val plans = snap?.plans.orEmpty().filter { it.enabled }
    val proposal = ScheduleEngine.propose(wake, sleep, plans.map { MealType.parse(it.type) }, s.hasReflux, plans.associate { MealType.parse(it.type) to it.durationMin })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.switch_auto_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NoteText(stringResource(R.string.switch_auto_text))
                TimeRow(stringResource(R.string.wake_time), wake) { pick = 1 }
                TimeRow(stringResource(R.string.sleep_time), sleep) { pick = 2 }
                proposal.meals.forEach { m ->
                    val old = plans.firstOrNull { MealType.parse(it.type) == m.type }?.minuteOfDay
                    Text(
                        if (old != null && old != m.minuteOfDay) stringResource(R.string.change_line, mealName(m.type), TimeFmt.clock(old), TimeFmt.clock(m.minuteOfDay))
                        else stringResource(R.string.meal_row_value, mealName(m.type) + " · " + TimeFmt.clock(m.minuteOfDay), m.durationMin),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                ProposalNotes(proposal)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(wake, sleep) }, enabled = proposal.meals.isNotEmpty()) { Text(stringResource(R.string.action_activate_auto)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (pick != 0) {
        TimePickerDialog(stringResource(if (pick == 1) R.string.wake_time else R.string.sleep_time), if (pick == 1) wake else sleep,
            onDismiss = { pick = 0 }, onConfirm = { if (pick == 1) wake = it else sleep = it; pick = 0 })
    }
}

/** In the automatic mode a time change is explicit: this day only, or switch to manual. */
@Composable
private fun AutoEditDialog(p: MealPlanEntity, onDismiss: () -> Unit, onThisDay: () -> Unit, onSwitchManual: () -> Unit) {
    val name = p.label.ifBlank { mealName(MealType.parse(p.type)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_time_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteText(stringResource(R.string.edit_time_auto_note))
                SecondaryButton(stringResource(R.string.edit_this_day), onThisDay)
                SecondaryButton(stringResource(R.string.edit_switch_manual), onSwitchManual)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrinkScheduleCard(s: AppSettings, auto: Boolean, onPick: (Picker) -> Unit, save: (suspend () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    fun upd(block: (AppSettings) -> AppSettings) = save { g.settings.update(block) }
    SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text(stringResource(R.string.drinks_card), style = MaterialTheme.typography.titleLarge)
        SwitchRow(stringResource(R.string.hydration_enabled), s.hydrationEnabled, { v -> upd { it.copy(hydrationEnabled = v) } }, stringResource(R.string.hydration_enabled_desc))
        if (s.hydrationEnabled) {
            Text(stringResource(R.string.drink_style), style = MaterialTheme.typography.titleMedium)
            listOf(DrinkReminderStyle.ALARM to R.string.drink_style_alarm, DrinkReminderStyle.LIGHT to R.string.drink_style_light).forEach { (st, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(s.drinkStyle == st, role = Role.RadioButton) { upd { it.copy(drinkStyle = st) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.drinkStyle == st, onClick = null)
                    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (auto) {
                NoteText(stringResource(R.string.drinks_every_auto, s.hydrationIntervalMin))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.drinkScheduleMode == DrinkScheduleMode.INTERVAL, { upd { it.copy(drinkScheduleMode = DrinkScheduleMode.INTERVAL) } }, { Text(stringResource(R.string.drink_mode_interval)) })
                    FilterChip(s.drinkScheduleMode == DrinkScheduleMode.TIMES, { upd { it.copy(drinkScheduleMode = DrinkScheduleMode.TIMES) } }, { Text(stringResource(R.string.drink_mode_times)) })
                }
            }
            if (auto || s.drinkScheduleMode == DrinkScheduleMode.INTERVAL) {
                if (!auto) {
                    TimeRow(stringResource(R.string.drink_from), s.drinkStart) {
                        onPick(Picker(ctx.getString(R.string.drink_from), s.drinkStart) { v -> upd { it.copy(drinkStart = v) } })
                    }
                    TimeRow(stringResource(R.string.drink_to), s.drinkEnd) {
                        onPick(Picker(ctx.getString(R.string.drink_to), s.drinkEnd) { v -> upd { it.copy(drinkEnd = v) } })
                    }
                }
                Stepper(stringResource(R.string.drink_interval_label), s.hydrationIntervalMin, stringResource(R.string.unit_min), { v -> upd { it.copy(hydrationIntervalMin = v) } }, 30..240, 15)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.drinkTimes.forEach { m ->
                        val label = TimeFmt.clock(m)
                        val del = stringResource(R.string.cd_delete, label)
                        InputChip(
                            selected = false, onClick = { upd { it.copy(drinkTimes = it.drinkTimes - m) } },
                            label = { Text(label) },
                            trailingIcon = { Icon(Icons.Default.Close, null) },
                            modifier = Modifier.semantics { contentDescription = del },
                        )
                    }
                }
                if (s.drinkTimes.isEmpty()) NoteText(stringResource(R.string.drink_times_empty))
                SecondaryButton(stringResource(R.string.add_drink_time), {
                    onPick(Picker(ctx.getString(R.string.add_drink_time), 10 * 60) { v -> upd { it.copy(drinkTimes = (it.drinkTimes + v).distinct().sorted()) } })
                })
            }
            Text(stringResource(R.string.repeat_days), style = MaterialTheme.typography.titleSmall)
            DaysChips(s.drinkDaysMask) { mask -> upd { it.copy(drinkDaysMask = mask) } }
            NoteText(stringResource(R.string.hydration_interval_note))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayAssignment(sn: ScheduleSnapshot, save: (suspend () -> Unit) -> Unit) {
    val g = LocalContext.current.graph
    SectionCard {
        Text(stringResource(R.string.weekday_assignment), style = MaterialTheme.typography.titleMedium)
        val order = if (AppLocale.isArabic()) listOf(6, 7, 1, 2, 3, 4, 5) else listOf(1, 2, 3, 4, 5, 6, 7)
        order.forEach { d ->
            val day = DayOfWeek.of(d)
            val current = sn.assignment.byWeekday[day] ?: sn.assignment.defaultProfileId
            Text(day.getDisplayName(TextStyle.FULL, AppLocale.current()), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sn.profiles.forEach { p ->
                    FilterChip(selected = current == p.id, onClick = { save { g.repo.setWeekdayProfile(day, p.id) } }, label = { Text(p.name) })
                }
            }
        }
    }
}
