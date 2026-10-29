package com.waqtak.app.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waqtak.app.MainActivity
import com.waqtak.app.R
import com.waqtak.app.domain.Alert
import com.waqtak.app.domain.AlertKind
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.graph
import com.waqtak.app.ui.art.Art
import com.waqtak.app.ui.art.FlatArt
import com.waqtak.app.ui.art.art
import com.waqtak.app.ui.components.BigButton
import com.waqtak.app.ui.components.SecondaryButton
import com.waqtak.app.ui.components.Stepper
import com.waqtak.app.ui.components.mealName
import com.waqtak.app.ui.theme.WaqtakTheme
import kotlinx.coroutines.launch
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.ui.screens.drinkName
import com.waqtak.app.util.TimeFmt
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

/**
 * Full-screen alarm UI shown over the lock screen. It does not unlock the device; the sound
 * lives in [AlarmService], so rotation or the back key never stop or duplicate it.
 */
class AlarmActivity : AppCompatActivity() {
    companion object {
        fun intent(ctx: Context, a: Alert): Intent =
            AlarmService.putAlert(Intent(ctx, AlarmActivity::class.java), a)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private var intentAlert by mutableStateOf<Alert?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty) {
            // first-ever screen could be an alarm: apply the default language (the sound lives in the service)
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("ar"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        intentAlert = AlarmService.readAlert(intent)
        // Back does not dismiss the alarm: only the explicit buttons do.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            WaqtakTheme {
                val queue by RingingHub.alerts.collectAsState()
                val resolved by RingingHub.resolved.collectAsState()
                val fallback = intentAlert?.takeIf { !RingingHub.isResolved(it, resolved) }
                val alert = queue.firstOrNull() ?: fallback
                LaunchedEffect(alert == null) {
                    if (alert == null) finish()
                }
                if (alert != null) AlarmScreen(alert, waiting = (queue.size - 1).coerceAtLeast(0), onAction = ::act)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AlarmService.readAlert(intent)?.let { intentAlert = it }
    }

    private fun act(alert: Alert, action: UserAction, duration: Int?, fullness: Int?, drink: Pair<DrinkType, Int>?) {
        AlarmController.silenceNow(alert.id) // immediately, on the click
        RingingHub.markResolved(alert.id)
        val app = applicationContext
        app.graph.scope.launch {
            AlarmController.perform(
                app, alert.id, action, duration, fullness,
                drinkType = drink?.first ?: DrinkType.WATER, amountMl = drink?.second,
            )
        }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (action == UserAction.START && !locked) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ROUTE, "timer")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        if (RingingHub.alerts.value.none { it.id != alert.id }) finish()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmScreen(alert: Alert, waiting: Int, onAction: (Alert, UserAction, Int?, Int?, Pair<DrinkType, Int>?) -> Unit) {
    val time = remember(alert.scheduledAt) { TimeFmt.time(alert.scheduledAt) }
    var duration by rememberSaveable(alert.id) { mutableIntStateOf(alert.durationMin.takeIf { it > 0 } ?: 20) }
    var fullness by rememberSaveable(alert.id) { mutableStateOf<Int?>(null) }
    var busy by remember(alert.id) { mutableStateOf(false) }
    var drinkType by rememberSaveable(alert.id) { mutableStateOf(DrinkType.WATER) }
    var drinkMl by rememberSaveable(alert.id) { mutableIntStateOf(alert.durationMin.takeIf { it > 0 } ?: 250) }
    fun fire(a: UserAction, d: Int? = null, f: Int? = null) {
        if (busy) return
        busy = true
        onAction(alert, a, d, f, drinkType to drinkMl)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val art = when (alert.kind) {
            AlertKind.MEAL_START -> alert.mealType.art()
            AlertKind.MEAL_END -> Art.MEAL_END
            AlertKind.TEST -> Art.BELL
            AlertKind.DRINK -> drinkType.art()
        }
        Spacer(Modifier.height(8.dp))
        FlatArt(art, Modifier.size(200.dp), animated = true)
        when (alert.kind) {
            AlertKind.MEAL_START -> {
                Text(
                    alert.label.ifBlank { mealName(alert.mealType) },
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.alarm_start_text, time), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Stepper(
                    label = stringResource(R.string.meal_duration),
                    value = duration,
                    unit = stringResource(R.string.unit_min),
                    onChange = { duration = it },
                    range = MealDefaults.MIN_DURATION..MealDefaults.MAX_DURATION,
                )
                BigButton(stringResource(R.string.action_start_eating), { fire(UserAction.START, duration) }, enabled = !busy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.action_snooze_10), { fire(UserAction.SNOOZE) }, Modifier.weight(1f), enabled = !busy)
                    SecondaryButton(stringResource(R.string.action_skip_meal), { fire(UserAction.SKIP) }, Modifier.weight(1f), enabled = !busy)
                }
                Text(stringResource(R.string.alarm_start_drink_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            AlertKind.MEAL_END -> {
                Text(
                    alert.label.ifBlank { mealName(alert.mealType) },
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.alarm_end_text), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(R.string.fullness_question), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to R.string.fullness_hungry, 2 to R.string.fullness_ok, 3 to R.string.fullness_full).forEach { (v, res) ->
                        FilterChip(selected = fullness == v, onClick = { fullness = if (fullness == v) null else v }, label = { Text(stringResource(res)) })
                    }
                }
                BigButton(stringResource(R.string.action_ok), { fire(UserAction.END_OK, f = fullness) }, enabled = !busy)
                SecondaryButton(stringResource(R.string.action_need_5_more), { fire(UserAction.END_EXTEND) }, enabled = !busy)
            }
            AlertKind.DRINK -> {
                Text(
                    stringResource(R.string.drink_reminder_text),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(time, style = MaterialTheme.typography.titleMedium)
                // water first; every drink stays available, nothing is required
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DrinkType.entries.filter { it != DrinkType.CUSTOM }.forEach { t ->
                        FilterChip(selected = drinkType == t, onClick = { drinkType = t }, label = { Text(drinkName(t)) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(150, 250, 330, 500).forEach { ml ->
                        FilterChip(selected = drinkMl == ml, onClick = { drinkMl = ml }, label = { Text(stringResource(R.string.ml_value, ml)) })
                    }
                }
                BigButton(stringResource(R.string.action_drank_type_amount, drinkMl, drinkName(drinkType)), { fire(UserAction.DRINK_LOG) }, enabled = !busy)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.action_close_only), { fire(UserAction.DRINK_CLOSE) }, Modifier.weight(1f), enabled = !busy)
                    SecondaryButton(stringResource(R.string.action_snooze_15), { fire(UserAction.DRINK_SNOOZE) }, Modifier.weight(1f), enabled = !busy)
                }
                Text(stringResource(R.string.drink_alarm_note), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            AlertKind.TEST -> {
                Text(stringResource(R.string.alarm_test_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(stringResource(R.string.alarm_test_text), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                BigButton(stringResource(R.string.action_ok), { fire(UserAction.TEST_OK) }, enabled = !busy)
            }
        }
        if (waiting > 0) Text(stringResource(R.string.alarm_more_waiting, waiting), style = MaterialTheme.typography.bodyMedium)
    }
}
