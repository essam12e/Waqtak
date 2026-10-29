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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.waqtak.app.R
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.data.PlanDraft
import com.waqtak.app.data.db.MealPlanEntity
import com.waqtak.app.data.db.ProfileEntity
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.ProfileKind
import com.waqtak.app.domain.ScheduleEngine
import com.waqtak.app.domain.ScheduleMode
import com.waqtak.app.domain.formatMinute
import com.waqtak.app.graph
import com.waqtak.app.ui.art.FlatArt
import com.waqtak.app.ui.art.art
import com.waqtak.app.ui.components.ConfirmDialog
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.ui.components.Stepper
import com.waqtak.app.ui.components.TimePickerDialog
import com.waqtak.app.ui.components.TimeRow
import com.waqtak.app.ui.components.mealName
import com.waqtak.app.util.AppLocale
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.format.TextStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val snap by g.repo.scheduleFlow().collectAsState(initial = null)
    val settings by g.settings.flow.collectAsState(initial = null)
    var selected by rememberSaveable { mutableLongStateOf(-1L) }
    var picker by remember { mutableStateOf<Triple<String, Int, (Int) -> Unit>?>(null) }
    var redistribute by remember { mutableStateOf(false) }
    var deletePlan by remember { mutableStateOf<MealPlanEntity?>(null) }
    var deleteProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    var addProfile by remember { mutableStateOf(false) }

    fun save(block: suspend () -> Unit) {
        scope.launch {
            block()
            AlarmScheduler.rescheduleAll(ctx)
        }
    }

    val sn = snap ?: return
    val s = settings ?: return
    val profile = sn.profiles.firstOrNull { it.id == selected } ?: sn.profiles.firstOrNull()
    ScreenColumn(stringResource(R.string.schedule_title)) {
        NoteText(stringResource(R.string.schedule_note))
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
            return@ScreenColumn
        }
        val plans = sn.plans.filter { it.profileId == profile.id }
            .sortedBy { ScheduleEngine.relative(profile.wakeMinute, it.minuteOfDay) }
        val issues = ScheduleEngine.review(
            profile.wakeMinute, profile.sleepMinute,
            plans.filter { it.enabled }.map { ScheduleEngine.MealSlot(it.id, it.minuteOfDay, it.durationMin) }, s.hasReflux,
        )

        SectionCard {
            Text(profile.name, style = MaterialTheme.typography.titleLarge)
            TimeRow(stringResource(R.string.wake_time), profile.wakeMinute) {
                picker = Triple(ctx.getString(R.string.wake_time), profile.wakeMinute) { v -> save { g.repo.updateProfile(profile.copy(wakeMinute = v)) } }
            }
            TimeRow(stringResource(R.string.sleep_time), profile.sleepMinute) {
                picker = Triple(ctx.getString(R.string.sleep_time), profile.sleepMinute) { v -> save { g.repo.updateProfile(profile.copy(sleepMinute = v)) } }
            }
            NoteText(stringResource(R.string.schedule_times_change_note))
            SecondaryButton(stringResource(R.string.action_redistribute), { redistribute = true })
            if (sn.profiles.size > 1) {
                TextButton(onClick = { deleteProfile = profile }) { Text(stringResource(R.string.action_delete_profile)) }
            }
        }

        plans.forEach { p ->
            val type = MealType.parse(p.type)
            val name = p.label.ifBlank { mealName(type) }
            SectionCard(container = if (p.enabled) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatArt(type.art(), Modifier.size(56.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(name, style = MaterialTheme.typography.titleLarge)
                        if (p.userEdited) NoteText(stringResource(R.string.edited_by_you))
                    }
                    val del = stringResource(R.string.cd_delete, name)
                    IconButton(onClick = { deletePlan = p }, modifier = Modifier.semantics { contentDescription = del }) { Icon(Icons.Default.Delete, null) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.meal_time), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        picker = Triple(name, p.minuteOfDay) { v -> save { g.repo.updatePlan(p.copy(minuteOfDay = v)) } }
                    }) { Text(formatMinute(p.minuteOfDay), style = MaterialTheme.typography.titleLarge) }
                }
                Stepper(stringResource(R.string.meal_duration), p.durationMin, stringResource(R.string.unit_min), { v ->
                    save { g.repo.updatePlan(p.copy(durationMin = v), markEdited = false) }
                }, MealDefaults.MIN_DURATION..MealDefaults.MAX_DURATION)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.meal_alarm_enabled), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = p.enabled, onCheckedChange = { on -> save { g.repo.updatePlan(p.copy(enabled = on), markEdited = false) } })
                }
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
                            val types = (plans.filter { it.enabled }.map { MealType.parse(it.type) } + t)
                            val prop = ScheduleEngine.propose(profile.wakeMinute, profile.sleepMinute, types, s.hasReflux)
                            val minute = prop.meals.firstOrNull { it.type == t }?.minuteOfDay ?: ((profile.wakeMinute + 60) % 1440)
                            g.repo.addPlan(profile.id, PlanDraft(t, "", minute, s.duration(t)), userEdited = false)
                        }
                    }, label = { Text("+ " + mealName(t)) })
                }
            }
        }

        if (sn.profiles.size > 1) {
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
            SectionCard {
                Text(stringResource(R.string.today_profile), style = MaterialTheme.typography.titleMedium)
                NoteText(stringResource(R.string.today_profile_note))
                val zone = g.repo.clock.zone()
                val today = Instant.ofEpochMilli(g.repo.clock.now()).atZone(zone).toLocalDate()
                val override = sn.assignment.overrides[today]
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = override == null, onClick = { save { g.repo.setDateOverride(today, null) } }, label = { Text(stringResource(R.string.today_profile_auto)) })
                    sn.profiles.forEach { p ->
                        FilterChip(selected = override == p.id, onClick = { save { g.repo.setDateOverride(today, p.id) } }, label = { Text(p.name) })
                    }
                }
            }
        }
        if (s.scheduleMode == ScheduleMode.SHIFTS || sn.profiles.size > 1) {
            SecondaryButton(stringResource(R.string.add_profile), { addProfile = true })
        }
    }

    picker?.let { (title, minute, set) ->
        TimePickerDialog(title, minute, onDismiss = { picker = null }, onConfirm = { set(it); picker = null })
    }
    deletePlan?.let { p ->
        ConfirmDialog(stringResource(R.string.delete_meal_title), stringResource(R.string.delete_meal_text), stringResource(R.string.action_delete),
            onDismiss = { deletePlan = null }, onConfirm = { deletePlan = null; save { g.repo.deletePlan(p.id) } })
    }
    deleteProfile?.let { p ->
        ConfirmDialog(stringResource(R.string.delete_profile_title), stringResource(R.string.delete_profile_text, p.name), stringResource(R.string.action_delete),
            onDismiss = { deleteProfile = null }, onConfirm = { deleteProfile = null; selected = -1; save { g.repo.deleteProfile(p) } })
    }
    if (redistribute && profile != null) {
        RedistributeDialog(profile, sn.plans.filter { it.profileId == profile.id }, s.hasReflux, onDismiss = { redistribute = false }) { changes ->
            redistribute = false
            save { changes.forEach { (p, minute) -> g.repo.updatePlan(p.copy(minuteOfDay = minute, userEdited = false), markEdited = false) } }
        }
    }
    if (addProfile) AddProfileDialog(onDismiss = { addProfile = false }) { name, wake, sleep ->
        addProfile = false
        save {
            val id = g.repo.addProfile(name, ProfileKind.SHIFT, wake, sleep)
            val types = sn.plans.filter { it.profileId == profile?.id && it.enabled }.map { MealType.parse(it.type) }
                .ifEmpty { listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER) }
            ScheduleEngine.propose(wake, sleep, types, s.hasReflux).meals.forEach {
                g.repo.addPlan(id, PlanDraft(it.type, "", it.minuteOfDay, s.duration(it.type)), userEdited = false)
            }
            selected = id
        }
    }
}

@Composable
private fun RedistributeDialog(
    profile: ProfileEntity,
    plans: List<MealPlanEntity>,
    reflux: Boolean,
    onDismiss: () -> Unit,
    onApply: (List<Pair<MealPlanEntity, Int>>) -> Unit,
) {
    val enabled = plans.filter { it.enabled }.sortedBy { MealType.parse(it.type).order }
    val proposal = remember(profile, plans) {
        ScheduleEngine.propose(profile.wakeMinute, profile.sleepMinute, enabled.map { MealType.parse(it.type) }, reflux, enabled.associate { MealType.parse(it.type) to it.durationMin })
    }
    val suggested = proposal.meals.associate { it.type to it.minuteOfDay }
    val rows = enabled.mapNotNull { p -> suggested[MealType.parse(p.type)]?.let { p to it } }.filter { it.first.minuteOfDay != it.second }
    val checks = remember(rows) { mutableStateMapOf<Long, Boolean>().apply { rows.forEach { put(it.first.id, !it.first.userEdited) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.redistribute_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteText(stringResource(R.string.redistribute_note))
                if (rows.isEmpty()) NoteText(stringResource(R.string.redistribute_nothing))
                rows.forEach { (p, minute) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checks[p.id] == true, onCheckedChange = { checks[p.id] = it })
                        Column {
                            Text("${p.label.ifBlank { mealName(MealType.parse(p.type)) }}: ${formatMinute(p.minuteOfDay)} → ${formatMinute(minute)}", style = MaterialTheme.typography.titleSmall)
                            if (p.userEdited) NoteText(stringResource(R.string.redistribute_edited))
                        }
                    }
                }
                ProposalNotes(proposal)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(rows.filter { checks[it.first.id] == true }) }, enabled = rows.any { checks[it.first.id] == true }) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AddProfileDialog(onDismiss: () -> Unit, onAdd: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var wake by remember { mutableStateOf(14 * 60) }
    var sleep by remember { mutableStateOf(6 * 60) }
    var pick by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, label = { Text(stringResource(R.string.profile_name)) }, singleLine = true)
                TimeRow(stringResource(R.string.wake_time), wake) { pick = 1 }
                TimeRow(stringResource(R.string.sleep_time), sleep) { pick = 2 }
            }
        },
        confirmButton = {
            val fallback = stringResource(R.string.profile_shift_n)
            TextButton(onClick = { onAdd(name.ifBlank { fallback }, wake, sleep) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (pick != 0) {
        TimePickerDialog(stringResource(if (pick == 1) R.string.wake_time else R.string.sleep_time), if (pick == 1) wake else sleep,
            onDismiss = { pick = 0 }, onConfirm = { if (pick == 1) wake = it else sleep = it; pick = 0 })
    }
}
