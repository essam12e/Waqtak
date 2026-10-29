package com.waqtak.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.waqtak.app.R
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.OccurrenceStatus
import com.waqtak.app.graph
import com.waqtak.app.ui.components.NoteText
import com.waqtak.app.ui.components.SectionCard
import com.waqtak.app.util.TimeFmt
import java.time.Instant
import java.time.LocalDate

private data class DayStats(val date: LocalDate, val started: Int, val skipped: Int, val missed: Int, val fluidMl: Int, val caffeineItems: Int)

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val repo = ctx.graph.repo
    val zone = repo.clock.zone()
    val today = remember { Instant.ofEpochMilli(repo.clock.now()).atZone(zone).toLocalDate() }
    val first = today.minusDays(6)
    val from = TimeFmt.dayStart(first, zone)
    val to = TimeFmt.dayStart(today.plusDays(1), zone)
    val sessions by remember(from) { repo.sessionsFlow(from, to) }.collectAsState(initial = emptyList())
    val rows by remember(from) { repo.occurrenceRowsFlow(from, to) }.collectAsState(initial = emptyList())
    val drinks by remember(from) { repo.drinksFlow(from, to) }.collectAsState(initial = emptyList())

    fun dateOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val stats = (0..6).map { i ->
        val d = first.plusDays(i.toLong())
        DayStats(
            date = d,
            started = sessions.count { dateOf(it.startWall) == d },
            skipped = rows.count { it.status == OccurrenceStatus.SKIPPED.name && dateOf(it.scheduledAt) == d },
            missed = rows.count { it.status == OccurrenceStatus.MISSED.name && dateOf(it.scheduledAt) == d },
            fluidMl = drinks.filter { dateOf(it.timestamp) == d }.sumOf { it.amountMl },
            caffeineItems = drinks.count {
                dateOf(it.timestamp) == d && (it.type in setOf(DrinkType.TEA.name, DrinkType.COFFEE.name, DrinkType.ENERGY.name) || (it.caffeineMg ?: 0) > 0)
            },
        )
    }

    ScreenColumn(stringResource(R.string.history_title)) {
        NoteText(stringResource(R.string.history_note))
        SectionCard {
            Text(stringResource(R.string.history_fluids_chart), style = MaterialTheme.typography.titleMedium)
            val max = (stats.maxOfOrNull { it.fluidMl } ?: 0).coerceAtLeast(500)
            val bar = MaterialTheme.colorScheme.secondary
            val track = MaterialTheme.colorScheme.secondaryContainer
            val desc = stats.joinToString("; ") { "${TimeFmt.shortDay(it.date)}: ${it.fluidMl}" }
            Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = desc }) {
                val n = stats.size
                val slot = size.width / n
                val w = slot * 0.55f
                stats.forEachIndexed { i, st ->
                    val x = i * slot + (slot - w) / 2
                    drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(12f, 12f))
                    val h = size.height * st.fluidMl / max
                    drawRoundRect(bar, Offset(x, size.height - h), Size(w, h), CornerRadius(12f, 12f))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                stats.forEach { Text(TimeFmt.shortDay(it.date), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1) }
            }
        }
        SectionCard {
            stats.reversed().forEachIndexed { i, st ->
                if (i > 0) HorizontalDivider()
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(TimeFmt.date(st.date), style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NoteText(
                            stringResource(R.string.history_meals_line, st.started, st.skipped, st.missed) + "\n" +
                                stringResource(R.string.history_drinks_line, st.fluidMl, st.caffeineItems)
                        )
                    }
                }
            }
        }
    }
}
