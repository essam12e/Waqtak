package com.waqtak.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waqtak.app.R
import com.waqtak.app.alarm.AlarmController
import com.waqtak.app.alarm.AlarmScheduler
import com.waqtak.app.alarm.UserAction
import com.waqtak.app.alarm.Notifications
import com.waqtak.app.alarm.TimerSync
import com.waqtak.app.domain.EndReason
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.SessionState
import com.waqtak.app.graph
import com.waqtak.app.ui.art.FlatArt
import com.waqtak.app.ui.art.art
import com.waqtak.app.ui.components.BigButton
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.ui.components.Stepper
import com.waqtak.app.ui.components.formatDuration
import com.waqtak.app.ui.components.mealName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The timer is derived from the stored session every second (never a fresh countdown),
 * so rotating the screen or reopening the app never creates a new timer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val session by g.repo.currentSessionFlow().collectAsState(initial = null)
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    var busy by remember { mutableStateOf(false) }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            block()
            AlarmScheduler.rescheduleAll(ctx)
            delay(500)
            busy = false
        }
    }

    ScreenColumn(stringResource(R.string.timer_title)) {
        val s = session
        if (s == null) {
            val settings by g.settings.flow.collectAsState(initial = null)
            var type by rememberSaveable { mutableStateOf(MealType.LUNCH) }
            var minutes by rememberSaveable { mutableIntStateOf(0) }
            val d = if (minutes > 0) minutes else settings?.duration(type) ?: 20
            SectionCard {
                Text(stringResource(R.string.timer_none), style = MaterialTheme.typography.titleLarge)
                NoteText(stringResource(R.string.timer_none_desc))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t; minutes = 0 }, label = { Text(mealName(t)) })
                    }
                }
                Stepper(stringResource(R.string.meal_duration), d, stringResource(R.string.unit_min), { minutes = it }, MealDefaults.MIN_DURATION..MealDefaults.MAX_DURATION)
                BigButton(stringResource(R.string.action_start_eating), {
                    act {
                        val id = g.repo.startUnscheduledMeal(type, "", d)
                        TimerSync.show(ctx, id)
                    }
                }, enabled = !busy)
            }
            return@ScreenColumn
        }
        val remaining = remember(s, tick) { g.repo.remainingMs(s) }
        val progress = (1f - remaining.toFloat() / s.totalMs).coerceIn(0f, 1f)
        val name = s.label.ifBlank { mealName(MealType.parse(s.mealType)) }
        Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ring = MaterialTheme.colorScheme.primary
            val track = MaterialTheme.colorScheme.primaryContainer
            Canvas(Modifier.size(260.dp)) {
                val w = 14.dp.toPx()
                val inset = w / 2
                drawArc(track, 0f, 360f, false, Offset(inset, inset), Size(size.width - w, size.height - w), style = Stroke(w))
                drawArc(ring, -90f, 360f * progress, false, Offset(inset, inset), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            FlatArt(MealType.parse(s.mealType).art(), Modifier.size(170.dp), animated = true)
        }
        val timeText = if (remaining > 0) formatDuration(remaining) else stringResource(R.string.timer_time_up)
        val a11y = stringResource(R.string.timer_remaining, timeText)
        Text(
            timeText,
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = a11y; liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center,
        )
        NoteText(stringResource(R.string.timer_gentle), Modifier.fillMaxWidth())
        if (s.state == SessionState.END_RINGING.name) NoteText(stringResource(R.string.timer_end_ringing))
        BigButton(stringResource(R.string.action_finished_meal), {
            act {
                if (s.state == SessionState.END_RINGING.name) {
                    AlarmController.perform(ctx, "end:${s.id}", UserAction.END_OK)
                } else {
                    g.repo.finishSession(s.id, EndReason.USER_FINISHED)
                    Notifications.cancelTimer(ctx)
                }
            }
        }, enabled = !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.action_extend_5), {
                act {
                    if (s.state == SessionState.END_RINGING.name) {
                        AlarmController.perform(ctx, "end:${s.id}", UserAction.END_EXTEND)
                    } else {
                        g.repo.extendSession(s.id)
                        TimerSync.show(ctx, s.id)
                    }
                }
            }, Modifier.weight(1f), enabled = !busy)
        }
        NoteText(stringResource(R.string.timer_drink_ok))
    }
}
