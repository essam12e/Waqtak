package com.waqtak.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.waqtak.app.R
import com.waqtak.app.alarm.Reconciler
import com.waqtak.app.data.PlanDraft
import com.waqtak.app.data.ProfileDraft
import com.waqtak.app.domain.DefaultSchedule
import com.waqtak.app.domain.DrinkReminderStyle
import com.waqtak.app.domain.DrinkScheduleMode
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.ProfileKind
import com.waqtak.app.domain.ScheduleEngine
import com.waqtak.app.domain.ScheduleMode
import com.waqtak.app.graph
import com.waqtak.app.ui.art.Art
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
import com.waqtak.app.util.TimeFmt
import kotlinx.coroutines.launch

/** First run: three short cards (once), then a single setup screen, then permissions. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by ctx.graph.settings.flow.collectAsState(initial = null)
    val s = settings ?: return
    if (!s.introDone) {
        IntroCards(onFinish = { scope.launch { ctx.graph.settings.update { it.copy(introDone = true) } } })
    } else {
        SetupFlow(onDone)
    }
}

private data class IntroCard(val art: Art, val title: Int, val text: Int)

private val introCards = listOf(
    IntroCard(Art.LUNCH, R.string.intro_1_title, R.string.intro_1_text),
    IntroCard(Art.MEAL_END, R.string.intro_2_title, R.string.intro_2_text),
    IntroCard(Art.BELL, R.string.intro_3_title, R.string.intro_3_text),
)

/** Shown once after install (completion stored locally); can be replayed from Help. */
@Composable
fun IntroCards(onFinish: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val card = introCards[page]
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (page == 0) {
                Image(painterResource(R.drawable.waqtak_logo), contentDescription = stringResource(R.string.app_name), modifier = Modifier.size(150.dp))
                LanguageChips()
                Spacer(Modifier.height(12.dp))
            } else {
                FlatArt(card.art, Modifier.size(200.dp), animated = true)
                Spacer(Modifier.height(20.dp))
            }
            Text(
                stringResource(card.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(card.text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.intro_page, page + 1, introCards.size), style = MaterialTheme.typography.labelMedium)
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (page < introCards.size - 1) {
                    SecondaryButton(stringResource(R.string.action_skip), onFinish, Modifier.weight(1f))
                    BigButton(stringResource(R.string.action_next), { page++ }, Modifier.weight(1f))
                } else {
                    BigButton(stringResource(R.string.action_begin), onFinish, Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageChips() {
    val current = AppLocale.current().language
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("ar" to R.string.lang_ar, "en" to R.string.lang_en).forEach { (tag, label) ->
            FilterChip(
                selected = current == tag,
                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/** Editable meal row state for the setup screen (saved across rotation/language change). */
private data class MealRow(val type: MealType, val minute: Int, val duration: Int, val enabled: Boolean)

private val mealRowSaver = listSaver<SnapshotStateList<MealRow>, Int>(
    save = { l -> l.flatMap { listOf(it.type.ordinal, it.minute, it.duration, if (it.enabled) 1 else 0) } },
    restore = { l -> mutableStateListOf<MealRow>().apply { l.chunked(4).forEach { add(MealRow(MealType.entries[it[0]], it[1], it[2], it[3] == 1)) } } },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupFlow(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(ScheduleMode.MANUAL) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val manualMeals = rememberSaveable(saver = mealRowSaver) {
        mutableStateListOf<MealRow>().apply {
            DefaultSchedule.meals.forEach { add(MealRow(it.type, it.minuteOfDay, it.durationMin, true)) }
            add(MealRow(MealType.SNACK, 16 * 60 + 30, MealDefaults.durationFor(MealType.SNACK), false))
        }
    }
    var wake by rememberSaveable { mutableIntStateOf(DefaultSchedule.WAKE) }
    var sleep by rememberSaveable { mutableIntStateOf(DefaultSchedule.SLEEP) }
    var autoTypes by rememberSaveable { mutableStateOf(setOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)) }
    var reflux by rememberSaveable { mutableStateOf(false) }
    var drinkStart by rememberSaveable { mutableIntStateOf(DefaultSchedule.DRINK_START) }
    var drinkEnd by rememberSaveable { mutableIntStateOf(DefaultSchedule.DRINK_END) }
    var interval by rememberSaveable { mutableIntStateOf(DefaultSchedule.DRINK_INTERVAL) }
    var drinkStyle by rememberSaveable { mutableStateOf(DrinkReminderStyle.ALARM) }
    var showHealth by rememberSaveable { mutableStateOf(false) }
    var fluidRestriction by rememberSaveable { mutableStateOf(false) }
    var fluidGoal by rememberSaveable { mutableStateOf("") }
    var medical by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Triple<String, Int, (Int) -> Unit>?>(null) }
    var saving by remember { mutableStateOf(false) }

    val durations = manualMeals.associate { it.type to it.duration }
    val proposal = ScheduleEngine.propose(wake, sleep, autoTypes.toList(), reflux, durations)

    fun activate() {
        if (saving) return
        saving = true
        scope.launch {
            val g = ctx.graph
            val now = g.repo.clock.now()
            val profile = if (mode == ScheduleMode.AUTO) {
                ProfileDraft(
                    ProfileKind.WORK, ctx.getString(R.string.profile_everyday), wake, sleep,
                    proposal.meals.map { PlanDraft(it.type, "", it.minuteOfDay, it.durationMin) },
                )
            } else {
                // manual times are calendar times: the day starts at midnight
                ProfileDraft(
                    ProfileKind.CUSTOM, ctx.getString(R.string.profile_everyday), 0, 0,
                    manualMeals.filter { it.enabled }.map { PlanDraft(it.type, "", it.minute, it.duration) },
                )
            }
            g.repo.replaceSchedule(listOf(profile), emptyMap())
            val goal = fluidGoal.toIntOrNull()?.coerceIn(0, 10000) ?: 0
            g.settings.update {
                it.copy(
                    onboardingDone = true, introDone = true, scheduleMode = mode, hasReflux = reflux,
                    hasFluidRestriction = fluidRestriction, hasMedicalPlan = medical, fluidGoalMl = goal,
                    hydrationEnabled = !fluidRestriction, hydrationIntervalMin = interval,
                    drinkStyle = drinkStyle, drinkScheduleMode = DrinkScheduleMode.INTERVAL,
                    drinkStart = drinkStart, drinkEnd = drinkEnd,
                    durations = it.durations + durations,
                    autoWake = wake, autoSleep = sleep,
                    lastReconcileAt = now, drinkHandledUpTo = now,
                )
            }
            Reconciler.reconcile(ctx, "onboarding")
            onDone()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenColumn(title = null, modifier = Modifier.weight(1f)) {
            if (step == 0) {
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                SectionCard {
                    listOf(
                        ScheduleMode.AUTO to (R.string.mode_auto to R.string.mode_auto_desc),
                        ScheduleMode.MANUAL to (R.string.mode_manual to R.string.mode_manual_desc),
                    ).forEach { (m, res) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(mode == m, role = Role.RadioButton) { mode = m },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(stringResource(res.first), style = MaterialTheme.typography.titleMedium)
                                NoteText(stringResource(res.second))
                            }
                        }
                    }
                }
                NoteText(stringResource(R.string.setup_note))

                if (mode == ScheduleMode.AUTO) {
                    SectionCard {
                        TimeRow(stringResource(R.string.wake_time), wake) { picker = Triple(ctx.getString(R.string.wake_time), wake) { v -> wake = v } }
                        TimeRow(stringResource(R.string.sleep_time), sleep) { picker = Triple(ctx.getString(R.string.sleep_time), sleep) { v -> sleep = v } }
                        Text(stringResource(R.string.meals_label), style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MealType.entries.forEach { t ->
                                FilterChip(
                                    selected = t in autoTypes,
                                    onClick = { autoTypes = if (t in autoTypes) autoTypes - t else autoTypes + t },
                                    label = { Text(mealName(t)) },
                                )
                            }
                        }
                        SwitchRow(stringResource(R.string.health_reflux), reflux, { reflux = it }, stringResource(R.string.health_reflux_desc))
                    }
                    SectionCard {
                        Text(stringResource(R.string.your_schedule), style = MaterialTheme.typography.titleLarge)
                        proposal.meals.forEach { m -> MealPreviewRow(m.type, m.minuteOfDay, m.durationMin) }
                        ProposalNotes(proposal)
                        NoteText(stringResource(R.string.drinks_every_auto, interval))
                    }
                } else {
                    SectionCard {
                        Text(stringResource(R.string.your_schedule), style = MaterialTheme.typography.titleLarge)
                        manualMeals.forEachIndexed { i, m ->
                            if (!editing) {
                                if (m.enabled) MealPreviewRow(m.type, m.minute, m.duration)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FlatArt(m.type.art(), Modifier.size(40.dp))
                                    Text(mealName(m.type), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                                    Switch(checked = m.enabled, onCheckedChange = { manualMeals[i] = m.copy(enabled = it) })
                                }
                                if (m.enabled) {
                                    TimeRow(stringResource(R.string.meal_time), m.minute) {
                                        picker = Triple(ctx.getString(R.string.meal_time), m.minute) { v -> manualMeals[i] = manualMeals[i].copy(minute = v) }
                                    }
                                    Stepper(stringResource(R.string.meal_duration), m.duration, stringResource(R.string.unit_min), { v -> manualMeals[i] = m.copy(duration = v) }, MealDefaults.MIN_DURATION..MealDefaults.MAX_DURATION)
                                }
                            }
                        }
                        if (!editing) {
                            NoteText(stringResource(R.string.drinks_every_manual, interval, TimeFmt.clock(drinkStart), TimeFmt.clock(drinkEnd)))
                        } else {
                            Text(stringResource(R.string.drinks_card), style = MaterialTheme.typography.titleMedium)
                            TimeRow(stringResource(R.string.drink_from), drinkStart) { picker = Triple(ctx.getString(R.string.drink_from), drinkStart) { v -> drinkStart = v } }
                            TimeRow(stringResource(R.string.drink_to), drinkEnd) { picker = Triple(ctx.getString(R.string.drink_to), drinkEnd) { v -> drinkEnd = v } }
                        }
                    }
                }
                if (editing || mode == ScheduleMode.AUTO) {
                    SectionCard {
                        Stepper(stringResource(R.string.drink_interval_label), interval, stringResource(R.string.unit_min), { interval = it }, 30..240, 15)
                    }
                }
                SectionCard {
                    Text(stringResource(R.string.drink_style), style = MaterialTheme.typography.titleMedium)
                    listOf(DrinkReminderStyle.ALARM to R.string.drink_style_alarm, DrinkReminderStyle.LIGHT to R.string.drink_style_light).forEach { (st, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(drinkStyle == st, role = Role.RadioButton) { drinkStyle = st },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = drinkStyle == st, onClick = null)
                            Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                TextButton(onClick = { showHealth = !showHealth }) { Text(stringResource(R.string.health_optional)) }
                if (showHealth) {
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
                        SwitchRow(stringResource(R.string.health_medical), medical, { medical = it }, stringResource(R.string.health_medical_desc))
                    }
                }
            } else {
                Text(stringResource(R.string.onb_permissions_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                NoteText(stringResource(R.string.onb_permissions_note))
                PermissionCards()
            }
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step == 0) {
                    if (mode == ScheduleMode.MANUAL) {
                        SecondaryButton(
                            stringResource(if (editing) R.string.action_done_editing else R.string.action_edit_times),
                            { editing = !editing }, Modifier.weight(1f),
                        )
                    }
                    val canGo = if (mode == ScheduleMode.AUTO) proposal.meals.isNotEmpty() else manualMeals.any { it.enabled }
                    BigButton(stringResource(R.string.action_activate_mine), { editing = false; step = 1 }, Modifier.weight(1f), enabled = canGo)
                } else {
                    SecondaryButton(stringResource(R.string.action_back), { step = 0 }, Modifier.weight(1f))
                    BigButton(stringResource(R.string.action_begin), { activate() }, Modifier.weight(1f), enabled = !saving)
                }
            }
        }
    }
    picker?.let { (title, minute, set) ->
        TimePickerDialog(title, minute, onDismiss = { picker = null }, onConfirm = { set(it); picker = null })
    }
}

@Composable
fun MealPreviewRow(type: MealType, minute: Int, duration: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FlatArt(type.art(), Modifier.size(44.dp))
        Text(mealName(type), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        Text(stringResource(R.string.meal_row_value, TimeFmt.clock(minute), duration), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ProposalNotes(p: ScheduleEngine.Proposal) {
    p.notes.forEach { n ->
        val text = when (n) {
            ScheduleEngine.Note.INVALID_TIMES -> stringResource(R.string.note_invalid_times)
            ScheduleEngine.Note.SHORT_DAY -> stringResource(R.string.note_short_day)
            ScheduleEngine.Note.SHORT_SPACING -> stringResource(R.string.note_short_spacing)
            ScheduleEngine.Note.REFLUX_GAP_APPLIED -> stringResource(R.string.note_reflux)
            ScheduleEngine.Note.ROOM_FOR_SNACK -> stringResource(R.string.note_room_for_snack)
            ScheduleEngine.Note.CROSSES_MIDNIGHT -> stringResource(R.string.note_crosses_midnight)
        }
        NoteText("• $text")
    }
}
