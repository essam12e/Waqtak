package com.waqtak.app.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.waqtak.app.data.ScheduleSnapshot
import com.waqtak.app.data.db.MealOccurrenceEntity
import com.waqtak.app.domain.Occurrence
import com.waqtak.app.domain.OccurrenceStatus
import com.waqtak.app.graph
import com.waqtak.app.util.TimeFmt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class TodayItem(val occurrence: Occurrence, val status: OccurrenceStatus?, val snoozeUntil: Long?)

data class TodayData(val items: List<TodayItem>, val snapshot: ScheduleSnapshot)

/**
 * Today's meals = occurrences of the current waking days that fall on today's calendar date
 * (meals after midnight of yesterday's waking day are included), merged with their state.
 */
fun todayFlow(ctx: Context, dayStart: Long): Flow<TodayData> {
    val repo = ctx.graph.repo
    val dayEnd = dayStart + 86_400_000L
    return combine(repo.scheduleFlow(), repo.occurrenceRowsFlow(dayStart - 86_400_000L, dayEnd + 86_400_000L)) { snap, rows ->
        val byKey: Map<String, MealOccurrenceEntity> = rows.associateBy { it.key }
        val occ = repo.occurrences(snap, dayStart, dayEnd)
        TodayData(
            occ.map { o -> TodayItem(o, byKey[o.key]?.status?.let { s -> OccurrenceStatus.valueOf(s) }, byKey[o.key]?.snoozeUntil) },
            snap,
        )
    }
}

@Composable
fun rememberToday(ctx: Context, now: Long): State<TodayData?> {
    val zone = ctx.graph.repo.clock.zone()
    val start = TimeFmt.dayStart(now, zone)
    val flow = remember(start) { todayFlow(ctx, start) }
    return flow.collectAsState(initial = null)
}
