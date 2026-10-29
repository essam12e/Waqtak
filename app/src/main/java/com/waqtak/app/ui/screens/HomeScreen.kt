package com.waqtak.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import com.waqtak.app.R
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.alarm.TimerSync
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.OccurrenceStatus
import com.waqtak.app.graph
import com.waqtak.app.ui.Routes
import com.waqtak.app.ui.art.Art
import com.waqtak.app.ui.art.FlatArt
import com.waqtak.app.ui.art.art
import com.waqtak.app.ui.components.BigButton
import com.waqtak.app.ui.components.ConfirmDialog
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.ui.components.formatDuration
import com.waqtak.app.ui.components.mealName
import com.waqtak.app.ui.goTab
import com.waqtak.app.util.TimeFmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun statusLabel(status: OccurrenceStatus?, snoozeUntil: Long?): String = when (status) {
    null -> stringResource(R.string.status_upcoming)
    OccurrenceStatus.RINGING -> stringResource(R.string.status_ringing)
    OccurrenceStatus.SNOOZED -> stringResource(R.string.status_snoozed, snoozeUntil?.let { TimeFmt.time(it) } ?: "")
    OccurrenceStatus.STARTED -> stringResource(R.string.status_started)
    OccurrenceStatus.SKIPPED -> stringResource(R.string.status_skipped)
    OccurrenceStatus.MISSED -> stringResource(R.string.status_missed)
}

@Composable
fun HomeScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    var readinessTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { readinessTick++ }
    val readiness = remember(readinessTick) { readReadiness(ctx) }
    val settings by g.settings.flow.collectAsState(initial = null)
    val today by rememberToday(ctx, now)
    val session by g.repo.currentSessionFlow().collectAsState(initial = null)
    val missed by g.repo.unseenMissedFlow().collectAsState(initial = emptyList())
    val expired by g.repo.unseenExpiredFlow().collectAsState(initial = emptyList())
    val zone = g.repo.clock.zone()
    val dayStart = TimeFmt.dayStart(now, zone)
    val drinks by remember(dayStart) { g.repo.drinksFlow(dayStart, dayStart + 86_400_000L) }.collectAsState(initial = emptyList())
    var confirmSkip by remember { mutableStateOf<TodayItem?>(null) }
    var busy by remember { mutableStateOf(false) }

    ScreenColumn(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.waqtak_logo), contentDescription = null, modifier = Modifier.size(64.dp))
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(TimeFmt.date(Instant.ofEpochMilli(now).atZone(zone).toLocalDate()), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (!readiness.essentialOk()) {
            SectionCard(container = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.clickable { nav.navigate(Routes.READINESS) }) {
                Text(stringResource(R.string.home_readiness_warning), style = MaterialTheme.typography.titleMedium)
                NoteText(stringResource(R.string.home_readiness_warning_desc))
                TextButton(onClick = { nav.navigate(Routes.READINESS) }) { Text(stringResource(R.string.open_readiness)) }
            }
        }

        if (missed.isNotEmpty() || expired.isNotEmpty()) {
            SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
                Text(stringResource(R.string.away_title), style = MaterialTheme.typography.titleLarge)
                missed.take(8).forEach {
                    NoteText("• " + stringResource(R.string.away_missed_meal, it.label.ifBlank { mealName(MealType.parse(it.mealType)) }, TimeFmt.time(it.scheduledAt)))
                }
                if (missed.size > 8) NoteText(stringResource(R.string.away_more, missed.size - 8))
                expired.forEach {
                    NoteText("• " + stringResource(R.string.away_timer_closed, it.label.ifBlank { mealName(MealType.parse(it.mealType)) }))
                }
                NoteText(stringResource(R.string.away_note))
                SecondaryButton(stringResource(R.string.action_got_it), { scope.launch { g.repo.markSummarySeen() } })
            }
        }

        session?.let { s ->
            val remaining = remember(s, now) { g.repo.remainingMs(s) }
            SectionCard(container = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.clickable { nav.navigate(Routes.TIMER) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatArt(MealType.parse(s.mealType).art(), Modifier.size(64.dp))
                    Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                        Text(stringResource(R.string.home_meal_running, s.label.ifBlank { mealName(MealType.parse(s.mealType)) }), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (remaining > 0) stringResource(R.string.timer_remaining, formatDuration(remaining)) else stringResource(R.string.timer_time_up),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
                TextButton(onClick = { nav.navigate(Routes.TIMER) }) { Text(stringResource(R.string.open_timer)) }
            }
        }

        val items = today?.items.orEmpty()
        val next = items.firstOrNull { it.status == null && it.occurrence.atMillis > now }
            ?: items.firstOrNull { it.status == OccurrenceStatus.SNOOZED }
        SectionCard {
            Text(stringResource(R.string.home_next), style = MaterialTheme.typography.titleLarge)
            if (next == null) {
                NoteText(stringResource(if (items.isEmpty()) R.string.home_no_meals_today else R.string.home_no_more_meals))
            } else {
                val o = next.occurrence
                val at = next.snoozeUntil ?: o.atMillis
                val (h, m) = TimeFmt.splitMinutes(at - now)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatArt(o.type.art(), Modifier.size(96.dp), animated = true)
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        Text(o.label.ifBlank { mealName(o.type) }, style = MaterialTheme.typography.headlineSmall)
                        Text(TimeFmt.time(at), style = MaterialTheme.typography.titleLarge)
                        NoteText(stringResource(R.string.home_in_time, h, m))
                    }
                }
                if (session == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(stringResource(R.string.action_start_now), {
                            if (busy) return@SecondaryButton
                            busy = true
                            scope.launch {
                                val s = g.settings.get()
                                g.repo.startOccurrenceNow(o, o.durationMin.takeIf { it > 0 } ?: s.duration(o.type))?.let { TimerSync.show(ctx, it) }
                                AlarmScheduler.rescheduleAll(ctx)
                                busy = false
                                nav.navigate(Routes.TIMER)
                            }
                        }, Modifier.weight(1f))
                        SecondaryButton(stringResource(R.string.action_skip_meal), { confirmSkip = next }, Modifier.weight(1f))
                    }
                }
            }
        }

        SectionCard {
            Text(stringResource(R.string.home_today), style = MaterialTheme.typography.titleLarge)
            if (items.isEmpty()) NoteText(stringResource(R.string.home_no_meals_today))
            items.forEach { it ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    FlatArt(it.occurrence.type.art(), Modifier.size(44.dp))
                    Text(
                        it.occurrence.label.ifBlank { mealName(it.occurrence.type) },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(TimeFmt.time(it.occurrence.atMillis), style = MaterialTheme.typography.titleMedium)
                        Text(statusLabel(it.status, it.snoozeUntil), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        val s = settings
        SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
            val total = drinks.sumOf { it.amountMl }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatArt(Art.WATER, Modifier.size(64.dp))
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.home_fluids), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.ml_value, total), style = MaterialTheme.typography.headlineSmall)
                    if (s != null && s.fluidGoalMl > 0) NoteText(stringResource(R.string.goal_value, s.fluidGoalMl))
                }
            }
            if (s != null && s.fluidGoalMl > 0) {
                LinearProgressIndicator(progress = { (total.toFloat() / s.fluidGoalMl).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            if (s != null) {
                BigButton(stringResource(R.string.action_drank_water_amount, s.defaultDrinkMl), {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            g.repo.logDrink(DrinkType.WATER, s.defaultDrinkMl, hasCalories = false)
                            delay(600)
                            busy = false
                        }
                    }
                }, container = MaterialTheme.colorScheme.secondary, enabled = !busy)
            }
            TextButton(onClick = { nav.goTab(Routes.DRINKS) }) { Text(stringResource(R.string.home_other_drink)) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.nav_readiness), { nav.navigate(Routes.READINESS) }, Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.nav_science), { nav.navigate(Routes.SCIENCE) }, Modifier.weight(1f))
        }
    }

    confirmSkip?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.skip_confirm_title),
            text = stringResource(R.string.skip_confirm_text),
            confirm = stringResource(R.string.action_skip_meal),
            onDismiss = { confirmSkip = null },
            onConfirm = {
                confirmSkip = null
                scope.launch {
                    g.repo.skipUpcoming(item.occurrence)
                    AlarmScheduler.rescheduleAll(ctx)
                }
            },
        )
    }
}
