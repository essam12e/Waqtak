package com.waqtak.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.selectable
import androidx.core.os.LocaleListCompat
import com.waqtak.app.R
import com.waqtak.app.alarm.Reconciler
import com.waqtak.app.data.PlanDraft
import com.waqtak.app.data.ProfileDraft
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.ProfileKind
import com.waqtak.app.domain.ScheduleEngine
import com.waqtak.app.domain.ScheduleMode
import com.waqtak.app.domain.formatMinute
import com.waqtak.app.graph
import com.waqtak.app.ui.art.FlatArt
import com.waqtak.app.ui.art.art
import com.waqtak.app.ui.components.BigButton
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.ui.components.Stepper
import com.waqtak.app.ui.components.SwitchRow
import com.waqtak.app.ui.components.TimePickerDialog
import com.waqtak.app.ui.components.TimeRow
import com.waqtak.app.ui.components.mealName
import com.waqtak.app.util.AppLocale
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle

private const val STEPS = 7

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var wake by rememberSaveable { mutableIntStateOf(7 * 60) }
    var sleep by rememberSaveable { mutableIntStateOf(23 * 60) }
    var offDifferent by rememberSaveable { mutableStateOf(false) }
    var offWake by rememberSaveable { mutableIntStateOf(9 * 60) }
    var offSleep by rememberSaveable { mutableIntStateOf(24 * 60 - 30) }
    val arabicWeek = AppLocale.isArabic()
    val workDays = rememberSaveable(saver = listSaver()) {
        (if (arabicWeek) listOf(7, 1, 2, 3, 4) else listOf(1, 2, 3, 4, 5)).toMutableStateList()
    }
    var mode by rememberSaveable { mutableStateOf(ScheduleMode.REGULAR) }
    val meals = rememberSaveable(saver = listSaver()) {
        listOf(MealType.BREAKFAST.ordinal, MealType.LUNCH.ordinal, MealType.DINNER.ordinal).toMutableStateList()
    }
    var reflux by rememberSaveable { mutableStateOf(false) }
    var fluidRestriction by rememberSaveable { mutableStateOf(false) }
    var fluidGoal by rememberSaveable { mutableStateOf("") }
    var medical by rememberSaveable { mutableStateOf(false) }
    // edited proposal: minute per meal type ordinal, -1 = use engine value
    val edits = rememberSaveable(saver = mapSaver()) { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    val offEdits = rememberSaveable(saver = mapSaver()) { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    var picker by remember { mutableStateOf<Triple<String, Int, (Int) -> Unit>?>(null) }
    var saving by remember { mutableStateOf(false) }

    val types = meals.map { MealType.entries[it] }
    val proposal = ScheduleEngine.propose(wake, sleep, types, reflux)
    val offProposal = ScheduleEngine.propose(offWake, offSleep, types, reflux)

    fun finish() {
        if (saving) return
        saving = true
        scope.launch {
            val g = ctx.graph
            fun drafts(p: ScheduleEngine.Proposal, e: Map<Int, Int>) = p.meals.map {
                PlanDraft(it.type, "", e[it.type.ordinal] ?: it.minuteOfDay, it.durationMin)
            }
            val work = ProfileDraft(
                kind = when (mode) { ScheduleMode.SHIFTS -> ProfileKind.SHIFT; ScheduleMode.CUSTOM -> ProfileKind.CUSTOM; else -> ProfileKind.WORK },
                name = ctx.getString(if (mode == ScheduleMode.SHIFTS) R.string.profile_shift_1 else if (offDifferent) R.string.profile_work else R.string.profile_everyday),
                wakeMinute = wake, sleepMinute = sleep, meals = drafts(proposal, edits),
            )
            val profiles = mutableListOf(work)
            val assign = mutableMapOf<DayOfWeek, Int>()
            if (offDifferent && mode == ScheduleMode.REGULAR) {
                profiles += ProfileDraft(ProfileKind.OFF, ctx.getString(R.string.profile_off), offWake, offSleep, drafts(offProposal, offEdits))
                DayOfWeek.entries.forEach { d -> assign[d] = if (d.value in workDays) 0 else 1 }
            }
            g.repo.replaceSchedule(profiles, assign)
            val goal = fluidGoal.toIntOrNull()?.coerceIn(0, 10000) ?: 0
            val now = g.repo.clock.now()
            g.settings.update {
                it.copy(
                    onboardingDone = true, scheduleMode = mode, hasReflux = reflux, hasFluidRestriction = fluidRestriction,
                    hasMedicalPlan = medical, fluidGoalMl = goal, hydrationEnabled = !fluidRestriction,
                    lastReconcileAt = now, drinkHandledUpTo = now,
                )
            }
            Reconciler.reconcile(ctx, "onboarding")
            onDone()
        }
    }

    ScreenColumn(title = null) {
        LinearProgressIndicator(progress = { (step + 1) / STEPS.toFloat() }, modifier = Modifier.fillMaxWidth())
        when (step) {
            0 -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Image(painterResource(R.drawable.waqtak_logo), contentDescription = stringResource(R.string.app_name), modifier = Modifier.size(200.dp))
                }
                Text(stringResource(R.string.onb_welcome_title), style = MaterialTheme.typography.headlineMedium)
                NoteText(stringResource(R.string.onb_welcome_text))
                SectionCard {
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
                    val current = AppLocale.current().language
                    listOf("ar" to R.string.lang_ar, "en" to R.string.lang_en).forEach { (tag, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(current == tag, role = Role.RadioButton) {
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current == tag, onClick = null)
                            Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                NoteText(stringResource(R.string.onb_disclaimer))
            }
            1 -> {
                Text(stringResource(R.string.onb_times_title), style = MaterialTheme.typography.headlineSmall)
                SectionCard {
                    TimeRow(stringResource(R.string.wake_time), wake) { picker = Triple(ctx.getString(R.string.wake_time), wake) { v -> wake = v; edits.clear() } }
                    TimeRow(stringResource(R.string.sleep_time), sleep) { picker = Triple(ctx.getString(R.string.sleep_time), sleep) { v -> sleep = v; edits.clear() } }
                    NoteText(stringResource(R.string.onb_times_note))
                }
                SectionCard {
                    SwitchRow(stringResource(R.string.onb_off_different), offDifferent, { offDifferent = it })
                    if (offDifferent) {
                        TimeRow(stringResource(R.string.wake_time_off), offWake) { picker = Triple(ctx.getString(R.string.wake_time_off), offWake) { v -> offWake = v; offEdits.clear() } }
                        TimeRow(stringResource(R.string.sleep_time_off), offSleep) { picker = Triple(ctx.getString(R.string.sleep_time_off), offSleep) { v -> offSleep = v; offEdits.clear() } }
                    }
                }
            }
            2 -> {
                Text(stringResource(R.string.onb_days_title), style = MaterialTheme.typography.headlineSmall)
                SectionCard {
                    Text(stringResource(R.string.work_days), style = MaterialTheme.typography.titleMedium)
                    val order = if (arabicWeek) listOf(6, 7, 1, 2, 3, 4, 5) else listOf(1, 2, 3, 4, 5, 6, 7)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        order.forEach { d ->
                            val sel = d in workDays
                            FilterChip(
                                selected = sel,
                                onClick = { if (sel) workDays.remove(d) else workDays.add(d) },
                                label = { Text(DayOfWeek.of(d).getDisplayName(TextStyle.FULL, AppLocale.current())) },
                            )
                        }
                    }
                    NoteText(stringResource(R.string.onb_days_note))
                }
                SectionCard {
                    Text(stringResource(R.string.schedule_type), style = MaterialTheme.typography.titleMedium)
                    listOf(
                        ScheduleMode.REGULAR to (R.string.mode_regular to R.string.mode_regular_desc),
                        ScheduleMode.SHIFTS to (R.string.mode_shifts to R.string.mode_shifts_desc),
                        ScheduleMode.CUSTOM to (R.string.mode_custom to R.string.mode_custom_desc),
                    ).forEach { (m, res) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(mode == m, role = Role.RadioButton) { mode = m },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = null)
                            androidx.compose.foundation.layout.Column {
                                Text(stringResource(res.first), style = MaterialTheme.typography.titleMedium)
                                NoteText(stringResource(res.second))
                            }
                        }
                    }
                }
            }
            3 -> {
                Text(stringResource(R.string.onb_meals_title), style = MaterialTheme.typography.headlineSmall)
                NoteText(stringResource(R.string.onb_meals_note))
                MealType.entries.forEach { t ->
                    val sel = t.ordinal in meals
                    SectionCard(container = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Row(
                            Modifier.fillMaxWidth().selectable(sel, role = Role.Checkbox) {
                                if (sel) meals.remove(t.ordinal) else meals.add(t.ordinal)
                                edits.clear(); offEdits.clear()
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FlatArt(t.art(), Modifier.size(56.dp))
                            Text(mealName(t), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding8())
                            androidx.compose.material3.Checkbox(checked = sel, onCheckedChange = null)
                        }
                    }
                }
            }
            4 -> {
                Text(stringResource(R.string.onb_health_title), style = MaterialTheme.typography.headlineSmall)
                NoteText(stringResource(R.string.onb_health_note))
                SectionCard {
                    SwitchRow(stringResource(R.string.health_reflux), reflux, { reflux = it; edits.clear(); offEdits.clear() }, stringResource(R.string.health_reflux_desc))
                }
                SectionCard {
                    SwitchRow(stringResource(R.string.health_fluid), fluidRestriction, { fluidRestriction = it }, stringResource(R.string.health_fluid_desc))
                    if (fluidRestriction) {
                        OutlinedTextField(
                            value = fluidGoal,
                            onValueChange = { v -> fluidGoal = v.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.health_fluid_goal)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                SectionCard {
                    SwitchRow(stringResource(R.string.health_medical), medical, { medical = it; if (it) mode = ScheduleMode.CUSTOM }, stringResource(R.string.health_medical_desc))
                }
            }
            5 -> {
                Text(stringResource(R.string.onb_review_title), style = MaterialTheme.typography.headlineSmall)
                NoteText(stringResource(if (mode == ScheduleMode.CUSTOM) R.string.onb_review_custom else R.string.onb_review_note))
                ProposalCard(stringResource(if (offDifferent) R.string.profile_work else R.string.profile_everyday), proposal, edits) { title, minute, set -> picker = Triple(title, minute, set) }
                if (offDifferent && mode == ScheduleMode.REGULAR) {
                    ProposalCard(stringResource(R.string.profile_off), offProposal, offEdits) { title, minute, set -> picker = Triple(title, minute, set) }
                }
            }
            6 -> {
                Text(stringResource(R.string.onb_permissions_title), style = MaterialTheme.typography.headlineSmall)
                NoteText(stringResource(R.string.onb_permissions_note))
                PermissionCards()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) SecondaryButton(stringResource(R.string.action_back), { step-- }, Modifier.weight(1f))
            val canNext = when (step) {
                3 -> meals.isNotEmpty()
                5 -> proposal.meals.isNotEmpty()
                else -> true
            }
            if (step < STEPS - 1) {
                BigButton(stringResource(R.string.action_next), { step++ }, Modifier.weight(1f), enabled = canNext)
            } else {
                BigButton(stringResource(R.string.action_activate), { finish() }, Modifier.weight(1f), enabled = !saving)
            }
        }
    }
    picker?.let { (title, minute, set) ->
        TimePickerDialog(title, minute, onDismiss = { picker = null }, onConfirm = { set(it); picker = null })
    }
}

private fun Modifier.padding8() = this.then(Modifier.padding(horizontal = 12.dp))

@Composable
private fun ProposalCard(
    title: String,
    p: ScheduleEngine.Proposal,
    edits: MutableMap<Int, Int>,
    pick: (String, Int, (Int) -> Unit) -> Unit,
) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (p.meals.isEmpty()) {
            NoteText(stringResource(R.string.note_invalid_times))
        }
        p.meals.forEach { m ->
            val minute = edits[m.type.ordinal] ?: m.minuteOfDay
            val name = mealName(m.type)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatArt(m.type.art(), Modifier.size(48.dp))
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding8())
                androidx.compose.material3.OutlinedButton(onClick = { pick(name, minute) { v -> edits[m.type.ordinal] = v } }) {
                    Text(formatMinute(minute), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        ProposalNotes(p)
    }
}

@Composable
fun ProposalNotes(p: ScheduleEngine.Proposal) {
    p.notes.forEach { n ->
        val text = when (n) {
            ScheduleEngine.Note.INVALID_TIMES -> stringResource(R.string.note_invalid_times)
            ScheduleEngine.Note.SHORT_DAY_REDUCED -> stringResource(R.string.note_short_day_reduced, p.dropped.map { mealName(it) }.joinToString(stringResource(R.string.list_separator)))
            ScheduleEngine.Note.SHORT_SPACING -> stringResource(R.string.note_short_spacing)
            ScheduleEngine.Note.REFLUX_GAP_APPLIED -> stringResource(R.string.note_reflux)
            ScheduleEngine.Note.ROOM_FOR_SNACK -> stringResource(R.string.note_room_for_snack)
            ScheduleEngine.Note.CROSSES_MIDNIGHT -> stringResource(R.string.note_crosses_midnight)
        }
        NoteText("• $text")
    }
}


private fun listSaver() = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<Int>, Int>(
    save = { it.toList() }, restore = { it.toMutableStateList() },
)

private fun mapSaver() = androidx.compose.runtime.saveable.Saver<MutableMap<Int, Int>, List<Int>>(
    save = { m -> m.flatMap { listOf(it.key, it.value) } },
    restore = { l -> androidx.compose.runtime.mutableStateMapOf<Int, Int>().apply { l.chunked(2).forEach { put(it[0], it[1]) } } },
)
