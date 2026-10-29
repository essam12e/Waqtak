package com.waqtak.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.MIN
import com.waqtak.app.at
import com.waqtak.app.domain.AlertKind
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.EndReason
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.OccurrenceStatus
import com.waqtak.app.domain.SessionState
import com.waqtak.app.installGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryTest {

    @Test
    fun nextMealIsFirstUpcoming_andNothingRingsForThePastAtActivation() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8, 30) // activation was at 06:00, so 08:00 is a real occurrence
        val next = g.repo.nextMealStart(clock.now())!!
        assertEquals(at(8), next.atMillis) // within grace: due now

        // activation after 08:00 must not ring for 08:00
        val (g2, clock2) = installGraph(com.waqtak.app.FakeClock(at(8, 10)))
        assertEquals(at(13), g2.repo.nextMealStart(clock2.now())!!.atMillis)
        assertTrue(g2.repo.claimDueMeals(clock2.now()).isEmpty())
    }

    @Test
    fun claimingADueMealIsIdempotent_duplicateBroadcastDoesNotRingTwice() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8, 0, 1) + 5_000
        val first = g.repo.claimDueMeals(clock.now())
        assertEquals(1, first.size)
        assertEquals(AlertKind.MEAL_START, first[0].kind)
        assertEquals(MealType.BREAKFAST, first[0].mealType)
        assertTrue(g.repo.claimDueMeals(clock.now()).isEmpty())
        assertEquals(at(13), g.repo.nextMealStart(clock.now())!!.atMillis)
    }

    @Test
    fun timerStartsOnlyOnConfirmation_andDoubleTapCreatesOneSession() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val alert = g.repo.claimDueMeals(clock.now()).single()
        assertTrue(g.repo.openSessions().isEmpty()) // ringing is not eating
        clock.advance(2 * MIN)
        val id1 = g.repo.startMeal(alert.ref, 20)
        val id2 = g.repo.startMeal(alert.ref, 20)
        assertNotNull(id1)
        assertEquals(id1, id2)
        val open = g.repo.openSessions()
        assertEquals(1, open.size)
        assertEquals(at(8, 2), open[0].startWall) // real start time is recorded
        assertEquals(20 * MIN, open[0].plannedMs)
    }

    @Test
    fun snoozeMovesOnlyThisMeal_andSkipDoesNotCreateATimer() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val alert = g.repo.claimDueMeals(clock.now()).single()
        assertTrue(g.repo.snoozeMeal(alert.ref))
        val next = g.repo.nextMealStart(clock.now())!!
        assertEquals(at(8, 10), next.atMillis)
        assertEquals(alert.ref, next.snoozedKey)
        // the rest of the day did not move
        clock.nowMs = at(8, 10)
        val again = g.repo.claimDueMeals(clock.now()).single()
        assertEquals(alert.id, again.id)
        assertTrue(g.repo.skipMeal(again.ref))
        assertFalse(g.repo.skipMeal(again.ref)) // second tap: no effect
        assertTrue(g.repo.openSessions().isEmpty())
        assertNull(g.repo.startMeal(again.ref)) // skipped meals cannot start a timer by a stale tap
        assertEquals(at(13), g.repo.nextMealStart(clock.now())!!.atMillis)
    }

    @Test
    fun longAbsence_marksMissedWithoutRinging() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(15, 30)
        assertTrue(g.repo.claimDueMeals(clock.now()).isEmpty())
        val missed = g.repo.unseenMissedFlow().first()
        assertEquals(listOf(MealType.BREAKFAST.name, MealType.LUNCH.name), missed.map { it.mealType })
        g.repo.markSummarySeen()
        assertTrue(g.repo.unseenMissedFlow().first().isEmpty())
        assertEquals(at(18), g.repo.nextMealStart(clock.now())!!.atMillis)
    }

    @Test
    fun mealEndRingsOnce_andExtendAddsFiveMinutes() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val a = g.repo.claimDueMeals(clock.now()).single()
        val id = g.repo.startMeal(a.ref, 20)!!
        clock.advance(10 * MIN)
        assertTrue(g.repo.claimDueSessionEnds(clock.now()).isEmpty())
        clock.advance(10 * MIN)
        val end = g.repo.claimDueSessionEnds(clock.now())
        assertEquals(1, end.size)
        assertEquals(AlertKind.MEAL_END, end[0].kind)
        assertTrue(g.repo.claimDueSessionEnds(clock.now()).isEmpty())
        assertTrue(g.repo.extendSession(id))
        val s = g.repo.session(id)!!
        assertEquals(SessionState.ACTIVE.name, s.state)
        assertEquals(5 * MIN, g.repo.remainingMs(s))
        assertTrue(g.repo.finishSession(id, EndReason.TIMER_ACK, fullness = 2))
        assertFalse(g.repo.finishSession(id, EndReason.TIMER_ACK)) // double tap
        assertEquals(2, g.repo.session(id)!!.fullness)
    }

    @Test
    fun timerIgnoresWallClockChangesWhileRunning() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val a = g.repo.claimDueMeals(clock.now()).single()
        val id = g.repo.startMeal(a.ref, 20)!!
        clock.elapsedMs += 5 * MIN
        clock.nowMs += 3 * 3_600_000L // user changed the clock
        assertEquals(15 * MIN, g.repo.remainingMs(g.repo.session(id)!!))
        assertEquals(clock.nowMs + 15 * MIN, g.repo.sessionEndWall(g.repo.session(id)!!))
    }

    @Test
    fun afterReboot_recentlyEndedTimerRingsOnce_oldTimerClosesSilently() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val a = g.repo.claimDueMeals(clock.now()).single()
        val id = g.repo.startMeal(a.ref, 20)!!
        // reboot: new boot count, monotonic clock restarts; 25 min passed in wall time
        clock.boot = 2; clock.elapsedMs = 30_000; clock.nowMs = at(8, 25)
        g.repo.reconcileSessions(clock.now(), emptySet())
        assertEquals(SessionState.ACTIVE.name, g.repo.session(id)!!.state)
        assertEquals(1, g.repo.claimDueSessionEnds(clock.now()).size)

        // second meal, device off for an hour
        clock.nowMs = at(13)
        g.repo.finishSession(id, EndReason.TIMER_ACK)
        val b = g.repo.claimDueMeals(clock.now()).single()
        val id2 = g.repo.startMeal(b.ref, 25)!!
        clock.boot = 3; clock.elapsedMs = 10_000; clock.nowMs = at(14, 30)
        g.repo.reconcileSessions(clock.now(), emptySet())
        val s2 = g.repo.session(id2)!!
        assertEquals(SessionState.FINISHED.name, s2.state)
        assertEquals(EndReason.EXPIRED_UNATTENDED.name, s2.endReason)
        assertTrue(g.repo.claimDueSessionEnds(clock.now()).isEmpty())
        assertEquals(1, g.repo.unseenExpiredFlow().first().size)
    }

    @Test
    fun drinkLogging_isIdempotentPerToken() = runBlocking {
        val (g, clock) = installGraph()
        assertTrue(g.repo.logDrink(DrinkType.WATER, 250, false, token = "reminder:1"))
        assertFalse(g.repo.logDrink(DrinkType.WATER, 250, false, token = "reminder:1"))
        assertTrue(g.repo.logDrink(DrinkType.COFFEE, 150, false, caffeineMg = 80))
        val list = g.repo.drinks(clock.now() - 1000, clock.now() + 1000)
        assertEquals(2, list.size)
        assertEquals(400, list.sumOf { it.amountMl })
    }

    @Test
    fun skipUpcomingFromScheduleScreen() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(7)
        val snap = g.repo.schedule()
        val breakfast = g.repo.occurrences(snap, clock.now(), at(9)).single()
        assertTrue(g.repo.skipUpcoming(breakfast))
        assertEquals(at(13), g.repo.nextMealStart(clock.now())!!.atMillis)
        clock.nowMs = at(8)
        assertTrue(g.repo.claimDueMeals(clock.now()).isEmpty())
        assertEquals(OccurrenceStatus.SKIPPED.name, g.db.occurrences().get(breakfast.key)!!.status)
    }

    @Test
    fun editingAPlanMarksItUserEdited_andDoesNotRingForPast() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(9)
        val lunch = g.db.schedule().plans().first { it.type == MealType.LUNCH.name }
        g.repo.updatePlan(lunch.copy(minuteOfDay = 8 * 60 + 45)) // moved to a time already past today
        val updated = g.db.schedule().plan(lunch.id)!!
        assertTrue(updated.userEdited)
        assertTrue(g.repo.claimDueMeals(clock.now()).none { it.mealType == MealType.LUNCH })
    }

    @Test
    fun defaultSchedule_8_13_19_andRestoreKeepsHistory() = runBlocking {
        val (g, clock) = installGraph()
        g.repo.logDrink(DrinkType.WATER, 250, false)
        g.repo.applyDefaultSchedule("x")
        val plans = g.db.schedule().plans().sortedBy { it.minuteOfDay }
        assertEquals(listOf(8 * 60, 13 * 60, 19 * 60), plans.map { it.minuteOfDay })
        assertEquals(listOf(20, 25, 20), plans.map { it.durationMin })
        assertEquals(1, g.db.drinks().all().size) // history kept
        val s = g.settings.get()
        assertEquals(com.waqtak.app.domain.ScheduleMode.MANUAL, s.scheduleMode)
        assertEquals(8 * 60, s.drinkStart); assertEquals(21 * 60, s.drinkEnd); assertEquals(90, s.hydrationIntervalMin)
        assertEquals(at(8), g.repo.nextMealStart(clock.now())!!.atMillis)
    }

    @Test
    fun autoMode_recomputesFutureTimes_butNotARunningMeal() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(8)
        val a = g.repo.claimDueMeals(clock.now()).single()
        val sessionId = g.repo.startMeal(a.ref, 20)!!
        val profile = g.db.schedule().profiles().single()
        g.repo.updateProfile(profile.copy(wakeMinute = 9 * 60, sleepMinute = 1 * 60)) // later day
        val changes = g.repo.recomputeAuto(reflux = false)
        assertTrue(changes.isNotEmpty())
        val byType = g.db.schedule().plans().associateBy { MealType.parse(it.type) }
        assertEquals(10 * 60, byType[MealType.BREAKFAST]!!.minuteOfDay)
        // running timer untouched
        val s = g.repo.session(sessionId)!!
        assertEquals(SessionState.ACTIVE.name, s.state)
        assertEquals(20 * MIN, s.plannedMs)
        // nothing rings for the past: new breakfast at 10:00 belongs to today, lunch etc. follow
        assertTrue(g.repo.claimDueMeals(clock.now()).isEmpty())
    }

    @Test
    fun thisDayOnly_movesOnlyToday() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(9)
        val snap = g.repo.schedule()
        val lunch = g.repo.occurrences(snap, clock.now(), at(23)).first { it.type == MealType.LUNCH }
        g.repo.setTimeForThisDay(lunch, 14 * 60 + 30)
        val today = g.repo.occurrences(g.repo.schedule(), clock.now(), at(23)).first { it.type == MealType.LUNCH }
        assertEquals(at(14, 30), today.atMillis)
        val tomorrow = g.repo.occurrences(g.repo.schedule(), at(0, 0, 2), at(23, 0, 2)).first { it.type == MealType.LUNCH }
        assertEquals(at(13, 0, 2), tomorrow.atMillis)
        assertEquals(13 * 60, g.db.schedule().plans().first { it.type == MealType.LUNCH.name }.minuteOfDay)
    }

    @Test
    fun editedTimeReplacesOldAlarm_andRepeatsDaily() = runBlocking {
        val (g, clock) = installGraph()
        clock.nowMs = at(9)
        val dinner = g.db.schedule().plans().first { it.type == MealType.DINNER.name }
        g.repo.updatePlan(dinner.copy(minuteOfDay = 19 * 60 + 15))
        val next = g.repo.occurrences(g.repo.schedule(), at(17), at(23, 59, 3)).filter { it.type == MealType.DINNER }
        assertEquals(listOf(at(19, 15), at(19, 15, 2), at(19, 15, 3)), next.map { it.atMillis })
    }
}
