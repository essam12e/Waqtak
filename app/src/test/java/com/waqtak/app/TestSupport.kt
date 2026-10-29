package com.waqtak.app

import androidx.test.core.app.ApplicationProvider
import com.waqtak.app.data.AppClock
import com.waqtak.app.data.PlanDraft
import com.waqtak.app.data.ProfileDraft
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.ProfileKind
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class FakeClock(
    var nowMs: Long,
    var elapsedMs: Long = 10_000_000L,
    var boot: Int = 1,
    val z: ZoneId = ZoneId.of("Asia/Riyadh"),
) : AppClock {
    override fun now() = nowMs
    override fun elapsed() = elapsedMs
    override fun bootCount() = boot
    override fun zone() = z

    fun advance(ms: Long) {
        nowMs += ms
        elapsedMs += ms
    }
}

val RIYADH: ZoneId = ZoneId.of("Asia/Riyadh")

fun at(h: Int, m: Int = 0, day: Int = 1): Long =
    ZonedDateTime.of(LocalDateTime.of(2026, 1, day, h, m), RIYADH).toInstant().toEpochMilli()

const val MIN = 60_000L

/** Installs a test graph (in-memory DB, fake clock) and a 08:00/13:00/18:00 schedule activated at 06:00. */
fun installGraph(clock: FakeClock = FakeClock(at(6))): Pair<AppGraph, FakeClock> {
    val app = ApplicationProvider.getApplicationContext<WaqtakApp>()
    val g = AppGraph(app, clock, inMemoryDb = true)
    app.graph = g
    runBlocking {
        g.settings.clear()
        g.repo.replaceSchedule(
            listOf(
                ProfileDraft(
                    ProfileKind.WORK, "Every day", 7 * 60, 23 * 60,
                    listOf(
                        PlanDraft(MealType.BREAKFAST, "", 8 * 60, 20),
                        PlanDraft(MealType.LUNCH, "", 13 * 60, 25),
                        PlanDraft(MealType.DINNER, "", 18 * 60, 20),
                    ),
                )
            ),
            emptyMap(),
        )
        g.settings.update { it.copy(onboardingDone = true, lastReconcileAt = clock.now(), drinkHandledUpTo = clock.now()) }
    }
    return g to clock
}
