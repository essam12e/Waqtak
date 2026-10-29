package com.waqtak.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waqtak.app.R
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.graph
import com.waqtak.app.ui.art.FlatArt
import com.waqtak.app.ui.art.art
import com.waqtak.app.ui.components.BigButton
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.ui.components.SwitchRow
import com.waqtak.app.util.TimeFmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

@Composable
fun drinkName(t: DrinkType): String = stringResource(
    when (t) {
        DrinkType.WATER -> R.string.drink_water
        DrinkType.TEA -> R.string.drink_tea
        DrinkType.COFFEE -> R.string.drink_coffee
        DrinkType.MILK -> R.string.drink_milk
        DrinkType.LABAN -> R.string.drink_laban
        DrinkType.JUICE -> R.string.drink_juice
        DrinkType.SODA -> R.string.drink_soda
        DrinkType.ENERGY -> R.string.drink_energy
        DrinkType.CUSTOM -> R.string.drink_custom
    }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrinksScreen() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val settings by g.settings.flow.collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val zone = g.repo.clock.zone()
    val dayStart = TimeFmt.dayStart(now, zone)
    val drinks by remember(dayStart) { g.repo.drinksFlow(dayStart, dayStart + 86_400_000L) }.collectAsState(initial = emptyList())

    var type by rememberSaveable { mutableStateOf(DrinkType.WATER) }
    var amount by rememberSaveable { mutableIntStateOf(250) }
    var customAmount by rememberSaveable { mutableStateOf("") }
    var customName by rememberSaveable { mutableStateOf("") }
    var caffeine by rememberSaveable { mutableStateOf("") }
    var calories by rememberSaveable { mutableStateOf(type.defaultHasCalories) }
    var token by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(2500); saved = false } }

    val effectiveAmount = customAmount.toIntOrNull()?.takeIf { it in 1..5000 } ?: amount

    ScreenColumn(stringResource(R.string.drinks_title)) {
        val total = drinks.sumOf { it.amountMl }
        val calorieMl = drinks.filter { it.hasCalories }.sumOf { it.amountMl }
        val caffeineItems = drinks.filter { it.type in CAFFEINE_TYPES || (it.caffeineMg ?: 0) > 0 }
        val knownMg = drinks.mapNotNull { it.caffeineMg }.sum()
        SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
            Text(stringResource(R.string.drinks_today_total, total), style = MaterialTheme.typography.headlineSmall)
            val s = settings
            if (s != null && s.fluidGoalMl > 0) NoteText(stringResource(if (s.hasFluidRestriction) R.string.goal_doctor else R.string.goal_value, s.fluidGoalMl))
            NoteText(stringResource(R.string.drinks_calorie_ml, calorieMl))
            NoteText(stringResource(R.string.drinks_caffeine_summary, caffeineItems.size, knownMg))
        }

        SectionCard {
            Text(stringResource(R.string.drinks_choose), style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DrinkType.entries.forEach { t ->
                    val sel = t == type
                    val label = drinkName(t)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        border = if (sel) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.width(96.dp).selectable(sel, role = Role.RadioButton) {
                            type = t; calories = t.defaultHasCalories; caffeine = ""
                        },
                    ) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            FlatArt(t.art(), Modifier.size(56.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            NoteText(stringResource(drinkInfo(type)))
            if (type == DrinkType.CUSTOM) {
                OutlinedTextField(customName, { customName = it.take(40) }, label = { Text(stringResource(R.string.drink_custom_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.amount), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(150, 250, 330, 500).forEach { a ->
                    FilterChip(selected = customAmount.isEmpty() && amount == a, onClick = { amount = a; customAmount = "" }, label = { Text(stringResource(R.string.ml_value, a)) })
                }
            }
            OutlinedTextField(
                customAmount, { customAmount = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.amount_custom)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(stringResource(R.string.has_calories), calories, { calories = it }, stringResource(R.string.has_calories_desc))
            if (type.mayContainCaffeine || type == DrinkType.CUSTOM) {
                OutlinedTextField(
                    caffeine, { caffeine = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.caffeine_mg_optional)) },
                    supportingText = { Text(stringResource(R.string.caffeine_mg_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            val s = settings
            if (s != null && s.eatingWindowEnabled && calories) {
                val minute = Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
                val inside = if (s.eatingWindowStart <= s.eatingWindowEnd) minute in s.eatingWindowStart..s.eatingWindowEnd
                else minute >= s.eatingWindowStart || minute <= s.eatingWindowEnd
                NoteText(stringResource(if (inside) R.string.window_inside else R.string.window_outside))
            }
            if (s != null && s.caffeineReminderEnabled && (type.mayContainCaffeine)) {
                NoteText(stringResource(R.string.caffeine_late_note, s.caffeineCutoffHours))
            }
            BigButton(stringResource(R.string.action_log_drink, effectiveAmount), {
                if (busy) return@BigButton
                busy = true
                val tk = token
                scope.launch {
                    val ok = g.repo.logDrink(
                        type = type, amountMl = effectiveAmount, hasCalories = calories,
                        caffeineMg = caffeine.toIntOrNull(), customName = customName.takeIf { type == DrinkType.CUSTOM && it.isNotBlank() },
                        token = tk,
                    )
                    token = UUID.randomUUID().toString()
                    customAmount = ""
                    caffeine = ""
                    saved = ok
                    delay(400)
                    busy = false
                }
            }, enabled = !busy)
            if (saved) Text(stringResource(R.string.drink_saved), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }

        SectionCard {
            Text(stringResource(R.string.drinks_today_list), style = MaterialTheme.typography.titleLarge)
            if (drinks.isEmpty()) NoteText(stringResource(R.string.drinks_none))
            drinks.forEach { d ->
                val t = DrinkType.parse(d.type)
                val name = d.customName ?: drinkName(t)
                val del = stringResource(R.string.cd_delete, name)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatArt(t.art(), Modifier.size(40.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text("$name · ${stringResource(R.string.ml_value, d.amountMl)}", style = MaterialTheme.typography.titleMedium)
                        val extra = listOfNotNull(
                            TimeFmt.time(d.timestamp),
                            if (d.hasCalories) stringResource(R.string.tag_calories) else null,
                            d.caffeineMg?.let { stringResource(R.string.tag_caffeine_mg, it) },
                        ).joinToString(" · ")
                        NoteText(extra)
                    }
                    IconButton(onClick = { scope.launch { g.repo.deleteDrink(d.id) } }, modifier = Modifier.semantics { contentDescription = del }) {
                        Icon(Icons.Default.Delete, null)
                    }
                }
            }
        }
        SectionCard {
            Text(stringResource(R.string.drinks_differences_title), style = MaterialTheme.typography.titleMedium)
            NoteText(stringResource(R.string.drinks_differences))
        }
    }
}

private fun drinkInfo(t: DrinkType): Int = when (t) {
    DrinkType.WATER -> R.string.info_water
    DrinkType.TEA, DrinkType.COFFEE -> R.string.info_tea_coffee
    DrinkType.MILK, DrinkType.LABAN -> R.string.info_milk
    DrinkType.JUICE -> R.string.info_juice
    DrinkType.SODA -> R.string.info_soda
    DrinkType.ENERGY -> R.string.info_energy
    DrinkType.CUSTOM -> R.string.info_custom
}

/** Counted as caffeine items in the daily summary; soda is counted only with a known caffeine amount. */
private val CAFFEINE_TYPES = setOf(DrinkType.TEA.name, DrinkType.COFFEE.name, DrinkType.ENERGY.name)
