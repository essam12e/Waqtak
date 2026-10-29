package com.waqtak.app.data

import androidx.room.withTransaction
import com.waqtak.app.data.db.DateOverrideEntity
import com.waqtak.app.data.db.DrinkLogEntity
import com.waqtak.app.data.db.MealOccurrenceEntity
import com.waqtak.app.data.db.MealPlanEntity
import com.waqtak.app.data.db.MealSessionEntity
import com.waqtak.app.data.db.ProfileEntity
import com.waqtak.app.data.db.WaqtakDatabase
import com.waqtak.app.data.db.WeekdayAssignmentEntity
import com.waqtak.app.domain.Alert
import com.waqtak.app.domain.AlertKind
import com.waqtak.app.domain.DayAssignment
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.EndReason
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.domain.MealTimer
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.Occurrence
import com.waqtak.app.domain.OccurrencePlanner
import com.waqtak.app.domain.OccurrenceStatus
import com.waqtak.app.domain.PlanSpec
import com.waqtak.app.domain.ProfileKind
import com.waqtak.app.domain.ProfileSpec
import com.waqtak.app.domain.SessionState
import com.waqtak.app.domain.TimerBase
import com.waqtak.app.domain.WakingWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

data class ScheduleSnapshot(
    val profiles: List<ProfileEntity>,
    val plans: List<MealPlanEntity>,
    val assignments: List<WeekdayAssignmentEntity>,
    val overrides: List<DateOverrideEntity>,
) {
    val profileSpecs: Map<Long, ProfileSpec> =
        profiles.associate { it.id to ProfileSpec(it.id, it.wakeMinute, it.sleepMinute) }
    val planSpecs: List<PlanSpec> = plans.map {
        PlanSpec(it.id, it.profileId, MealType.parse(it.type), it.label, it.minuteOfDay, it.durationMin, it.enabled, it.effectiveFrom)
    }
    val assignment: DayAssignment = DayAssignment(
        defaultProfileId = profiles.firstOrNull()?.id ?: -1,
        byWeekday = assignments.associate { DayOfWeek.of(it.isoDay) to it.profileId },
        overrides = overrides.associate { LocalDate.ofEpochDay(it.epochDay) to it.profileId },
    )
    val isEmpty get() = profiles.isEmpty()
}

data class PlanDraft(val type: MealType, val label: String, val minuteOfDay: Int, val durationMin: Int)

data class ProfileDraft(
    val kind: ProfileKind,
    val name: String,
    val wakeMinute: Int,
    val sleepMinute: Int,
    val meals: List<PlanDraft>,
)

class WaqtakRepository(
    val db: WaqtakDatabase,
    val settings: SettingsRepository,
    val clock: AppClock,
) {
    private val scheduleDao = db.schedule()
    private val occDao = db.occurrences()
    private val sessionDao = db.sessions()
    private val drinkDao = db.drinks()

    // ---------------------------------------------------------------- schedule

    fun scheduleFlow(): Flow<ScheduleSnapshot> = combine(
        scheduleDao.profilesFlow(), scheduleDao.plansFlow(), scheduleDao.assignmentsFlow(), scheduleDao.overridesFlow(),
    ) { p, m, a, o -> ScheduleSnapshot(p, m, a, o) }

    suspend fun schedule(): ScheduleSnapshot =
        ScheduleSnapshot(scheduleDao.profiles(), scheduleDao.plans(), scheduleDao.assignments(), scheduleDao.overrides())

    fun occurrences(snap: ScheduleSnapshot, from: Long, to: Long): List<Occurrence> =
        OccurrencePlanner.occurrences(from, to, clock.zone(), snap.profileSpecs, snap.planSpecs, snap.assignment)

    fun wakingWindows(snap: ScheduleSnapshot, from: Long, to: Long): List<WakingWindow> =
        OccurrencePlanner.wakingWindows(from, to, clock.zone(), snap.profileSpecs, snap.assignment)

    /** Replaces the whole schedule (first setup, or "rebuild" after confirmation). */
    suspend fun replaceSchedule(profiles: List<ProfileDraft>, weekdayToProfileIndex: Map<DayOfWeek, Int>) {
        val now = clock.now()
        db.withTransaction {
            scheduleDao.clearAssignments()
            scheduleDao.clearProfiles() // cascades to plans
            val ids = profiles.map { pd ->
                val id = scheduleDao.insertProfile(ProfileEntity(name = pd.name, kind = pd.kind.name, wakeMinute = pd.wakeMinute, sleepMinute = pd.sleepMinute))
                pd.meals.forEach { m ->
                    scheduleDao.insertPlan(
                        MealPlanEntity(profileId = id, type = m.type.name, label = m.label, minuteOfDay = m.minuteOfDay, durationMin = m.durationMin, effectiveFrom = now)
                    )
                }
                id
            }
            weekdayToProfileIndex.forEach { (day, idx) ->
                ids.getOrNull(idx)?.let { scheduleDao.upsertAssignment(WeekdayAssignmentEntity(day.value, it)) }
            }
        }
    }

    suspend fun addProfile(name: String, kind: ProfileKind, wake: Int, sleep: Int): Long =
        scheduleDao.insertProfile(ProfileEntity(name = name, kind = kind.name, wakeMinute = wake, sleepMinute = sleep))

    suspend fun updateProfile(p: ProfileEntity) = scheduleDao.updateProfile(p)

    suspend fun deleteProfile(p: ProfileEntity) {
        db.withTransaction {
            val remaining = scheduleDao.profiles().filter { it.id != p.id }
            if (remaining.isEmpty()) return@withTransaction
            scheduleDao.deleteProfile(p)
            scheduleDao.assignments().filter { it.profileId == p.id }.forEach {
                scheduleDao.upsertAssignment(it.copy(profileId = remaining.first().id))
            }
            scheduleDao.overrides().filter { it.profileId == p.id }.forEach { scheduleDao.deleteOverride(it.epochDay) }
        }
    }

    suspend fun setWeekdayProfile(day: DayOfWeek, profileId: Long) =
        scheduleDao.upsertAssignment(WeekdayAssignmentEntity(day.value, profileId))

    suspend fun setDateOverride(date: LocalDate, profileId: Long?) {
        if (profileId == null) scheduleDao.deleteOverride(date.toEpochDay())
        else scheduleDao.upsertOverride(DateOverrideEntity(date.toEpochDay(), profileId))
        scheduleDao.pruneOverrides(LocalDate.now(clock.zone()).minusDays(7).toEpochDay())
    }

    suspend fun addPlan(profileId: Long, draft: PlanDraft, userEdited: Boolean = true): Long =
        scheduleDao.insertPlan(
            MealPlanEntity(
                profileId = profileId, type = draft.type.name, label = draft.label, minuteOfDay = draft.minuteOfDay,
                durationMin = draft.durationMin, userEdited = userEdited, effectiveFrom = clock.now(),
            )
        )

    /** Saves a user edit. Moving a time re-arms it from now, so an edit never rings for the past. */
    suspend fun updatePlan(plan: MealPlanEntity, markEdited: Boolean = true) {
        val old = scheduleDao.plan(plan.id)
        val timeChanged = old == null || old.minuteOfDay != plan.minuteOfDay || (!old.enabled && plan.enabled)
        scheduleDao.updatePlan(
            plan.copy(
                userEdited = plan.userEdited || (markEdited && old?.minuteOfDay != plan.minuteOfDay),
                effectiveFrom = if (timeChanged) clock.now() else plan.effectiveFrom,
            )
        )
    }

    suspend fun deletePlan(id: Long) = scheduleDao.deletePlan(id)

    // ---------------------------------------------------------------- occurrences

    data class NextMeal(val occurrence: Occurrence?, val atMillis: Long, val snoozedKey: String?)

    /** Earliest pending meal start (or snoozed meal) that still needs an alarm. */
    suspend fun nextMealStart(now: Long): NextMeal? {
        val snap = schedule()
        if (snap.isEmpty) return null
        val occs = occurrences(snap, now - MealDefaults.START_GRACE_MS, now + 3 * 86_400_000L)
        val rows = occDao.inRange(now - MealDefaults.START_GRACE_MS - 86_400_000L, now + 3 * 86_400_000L).associateBy { it.key }
        val pending = occs.firstOrNull { rows[it.key] == null }
        val snoozed = occDao.snoozed().filter { it.snoozeUntil != null }.minByOrNull { it.snoozeUntil!! }
        val candidates = listOfNotNull(
            pending?.let { NextMeal(it, it.atMillis, null) },
            snoozed?.let { NextMeal(null, it.snoozeUntil!!, it.key) },
        )
        return candidates.minByOrNull { it.atMillis }
    }

    private fun Occurrence.toRow(status: OccurrenceStatus, now: Long, seen: Boolean = true) = MealOccurrenceEntity(
        key = key, planId = planId, mealType = type.name, label = label, scheduledAt = atMillis,
        durationMin = durationMin, status = status.name, updatedAt = now, seen = seen,
    )

    private fun MealOccurrenceEntity.toAlert() = Alert(
        id = "start:$key", kind = AlertKind.MEAL_START, mealType = MealType.parse(mealType), label = label,
        scheduledAt = snoozeUntil ?: scheduledAt, ref = key, durationMin = durationMin,
    )

    /**
     * Called when the meal alarm fires. Atomically claims every meal that is due now (marks it
     * RINGING) and reports meals that are too late to ring as missed. Each occurrence can be
     * claimed only once, so duplicate deliveries never produce a second alert.
     */
    suspend fun claimDueMeals(now: Long): List<Alert> = db.withTransaction {
        val snap = schedule()
        val out = mutableListOf<Alert>()
        val lookback = maxOf(now - MealDefaults.MAX_LOOKBACK_MS, settings.get().lastReconcileAt - MealDefaults.START_GRACE_MS)
        val occs = occurrences(snap, minOf(lookback, now - MealDefaults.START_GRACE_MS), now + 60_000)
        for (o in occs) {
            if (o.atMillis < now - MealDefaults.START_GRACE_MS) {
                occDao.insertIgnore(o.toRow(OccurrenceStatus.MISSED, now, seen = false))
            } else if (occDao.insertIgnore(o.toRow(OccurrenceStatus.RINGING, now)) != -1L) {
                out += occDao.get(o.key)!!.toAlert()
            }
        }
        for (s in occDao.snoozed()) {
            val until = s.snoozeUntil ?: continue
            if (until > now + 60_000) continue
            if (until < now - MealDefaults.START_GRACE_MS) {
                occDao.transition(s.key, listOf(OccurrenceStatus.SNOOZED.name), OccurrenceStatus.MISSED.name, now)
            } else if (occDao.transition(s.key, listOf(OccurrenceStatus.SNOOZED.name), OccurrenceStatus.RINGING.name, now) == 1) {
                out += s.copy(status = OccurrenceStatus.RINGING.name).toAlert()
            }
        }
        out
    }

    /** Marks old, never-handled meals as missed (for the "while you were away" summary). */
    suspend fun markMissed(now: Long) {
        db.withTransaction {
            val snap = schedule()
            val from = maxOf(now - MealDefaults.MAX_LOOKBACK_MS, settings.get().lastReconcileAt - MealDefaults.START_GRACE_MS)
            val to = now - MealDefaults.START_GRACE_MS
            if (to > from) {
                occurrences(snap, from, to).forEach { occDao.insertIgnore(it.toRow(OccurrenceStatus.MISSED, now, seen = false)) }
            }
            occDao.snoozed().filter { (it.snoozeUntil ?: 0) < to }.forEach {
                occDao.transition(it.key, listOf(OccurrenceStatus.SNOOZED.name), OccurrenceStatus.MISSED.name, now)
            }
        }
    }

    suspend fun ringingMealAlerts(): List<Alert> = occDao.ringing().map { it.toAlert() }

    /** A ringing meal that is no longer ringing anywhere (process died, reboot). */
    suspend fun abandonRinging(key: String, now: Long) {
        occDao.transition(key, listOf(OccurrenceStatus.RINGING.name), OccurrenceStatus.MISSED.name, now)
    }

    /** Re-rings an orphaned RINGING meal once more via the snooze path. */
    suspend fun requeueRinging(key: String, at: Long) {
        occDao.snooze(key, at, clock.now())
    }

    suspend fun snoozeMeal(key: String, minutes: Int = MealDefaults.SNOOZE_MINUTES): Boolean {
        val now = clock.now()
        return occDao.snooze(key, now + minutes * 60_000L, now) == 1
    }

    suspend fun skipMeal(key: String): Boolean = db.withTransaction {
        val now = clock.now()
        occDao.transition(key, listOf(OccurrenceStatus.RINGING.name, OccurrenceStatus.SNOOZED.name), OccurrenceStatus.SKIPPED.name, now) == 1
    }

    /** Skip an upcoming meal from the schedule screen before it rings. */
    suspend fun skipUpcoming(o: Occurrence): Boolean {
        val now = clock.now()
        return occDao.insertIgnore(o.toRow(OccurrenceStatus.SKIPPED, now)) != -1L ||
            occDao.transition(o.key, listOf(OccurrenceStatus.SNOOZED.name), OccurrenceStatus.SKIPPED.name, now) == 1
    }

    /**
     * "OK, I started eating". Records the real start time and starts the timer.
     * Idempotent: a double tap returns the same session and never creates a second timer.
     */
    suspend fun startMeal(key: String, durationMin: Int? = null): Long? = db.withTransaction {
        val now = clock.now()
        val row = occDao.get(key) ?: return@withTransaction null
        val moved = occDao.transition(
            key, listOf(OccurrenceStatus.RINGING.name, OccurrenceStatus.SNOOZED.name, OccurrenceStatus.MISSED.name),
            OccurrenceStatus.STARTED.name, now,
        )
        if (moved == 0 && row.status != OccurrenceStatus.STARTED.name) return@withTransaction null
        createSession(key, MealType.parse(row.mealType), row.label, durationMin ?: row.durationMin, now)
    }

    /** Start a scheduled meal early from the app (before its alarm). */
    suspend fun startOccurrenceNow(o: Occurrence, durationMin: Int): Long? = db.withTransaction {
        val now = clock.now()
        if (occDao.insertIgnore(o.toRow(OccurrenceStatus.STARTED, now)) == -1L) {
            val moved = occDao.transition(
                o.key, listOf(OccurrenceStatus.RINGING.name, OccurrenceStatus.SNOOZED.name, OccurrenceStatus.MISSED.name),
                OccurrenceStatus.STARTED.name, now,
            )
            if (moved == 0 && occDao.get(o.key)?.status != OccurrenceStatus.STARTED.name) return@withTransaction null
        }
        createSession(o.key, o.type, o.label, durationMin, now)
    }

    /** A meal that is not in the schedule. */
    suspend fun startUnscheduledMeal(type: MealType, label: String, durationMin: Int, token: String = UUID.randomUUID().toString()): Long =
        db.withTransaction { createSession("manual:$token", type, label, durationMin, clock.now())!! }

    private suspend fun createSession(key: String, type: MealType, label: String, durationMin: Int, now: Long): Long? {
        val minutes = durationMin.coerceIn(MealDefaults.MIN_DURATION, MealDefaults.MAX_DURATION)
        val id = sessionDao.insertIgnore(
            MealSessionEntity(
                occurrenceKey = key, mealType = type.name, label = label, startWall = now,
                startElapsed = clock.elapsed(), bootCount = clock.bootCount(), plannedMs = minutes * 60_000L,
                state = SessionState.ACTIVE.name,
            )
        )
        if (id != -1L) {
            // Only one running timer: close any older open session quietly.
            sessionDao.open().filter { it.id != id }.forEach {
                sessionDao.finish(it.id, now, EndReason.USER_FINISHED.name, null, true)
            }
            return id
        }
        return sessionDao.all().firstOrNull { it.occurrenceKey == key }?.id
    }

    fun currentSessionFlow(): Flow<MealSessionEntity?> = sessionDao.currentFlow()

    suspend fun openSessions(): List<MealSessionEntity> = sessionDao.open()

    suspend fun session(id: Long) = sessionDao.get(id)

    fun timerBase(s: MealSessionEntity) = TimerBase(s.startWall, s.startElapsed, s.bootCount, s.totalMs)

    fun remainingMs(s: MealSessionEntity): Long =
        MealTimer.remainingMs(timerBase(s), clock.now(), clock.elapsed(), clock.bootCount())

    fun sessionEndWall(s: MealSessionEntity): Long =
        MealTimer.endWall(timerBase(s), clock.now(), clock.elapsed(), clock.bootCount())

    private fun MealSessionEntity.toEndAlert() = Alert(
        id = "end:$id", kind = AlertKind.MEAL_END, mealType = MealType.parse(mealType), label = label,
        scheduledAt = startWall + totalMs, ref = id.toString(), durationMin = (totalMs / 60_000).toInt(),
    )

    /** Claims meal timers that have reached their end; each session rings at most once per end. */
    suspend fun claimDueSessionEnds(now: Long): List<Alert> {
        val out = mutableListOf<Alert>()
        for (s in sessionDao.open()) {
            if (s.state != SessionState.ACTIVE.name) continue
            val remaining = remainingMs(s)
            if (remaining > 30_000) continue
            if (-remaining > MealDefaults.END_GRACE_MS) {
                sessionDao.finish(s.id, now, EndReason.EXPIRED_UNATTENDED.name, null, false)
            } else if (sessionDao.transition(s.id, listOf(SessionState.ACTIVE.name), SessionState.END_RINGING.name) == 1) {
                out += s.toEndAlert()
            }
        }
        return out
    }

    suspend fun ringingEndAlerts(): List<Alert> =
        sessionDao.open().filter { it.state == SessionState.END_RINGING.name }.map { it.toEndAlert() }

    /** After a reboot: expire long-finished timers silently, re-arm a just-finished one once. */
    suspend fun reconcileSessions(now: Long, endRingingAlive: Set<Long>) {
        for (s in sessionDao.open()) {
            val overdue = -remainingMs(s)
            if (s.state == SessionState.END_RINGING.name && s.id in endRingingAlive) continue
            if (overdue > MealDefaults.END_GRACE_MS) {
                sessionDao.finish(s.id, now, EndReason.EXPIRED_UNATTENDED.name, null, false)
            } else if (s.state == SessionState.END_RINGING.name) {
                // the ringing died with the process; let the end alarm fire once more
                sessionDao.transition(s.id, listOf(SessionState.END_RINGING.name), SessionState.ACTIVE.name)
            }
        }
    }

    suspend fun finishSession(id: Long, reason: EndReason, fullness: Int? = null): Boolean =
        sessionDao.finish(id, clock.now(), reason.name, fullness, true) == 1

    suspend fun extendSession(id: Long, minutes: Int = MealDefaults.EXTEND_MINUTES): Boolean =
        sessionDao.extend(id, minutes * 60_000L) == 1

    suspend fun setFullness(id: Long, fullness: Int?) = sessionDao.setFullness(id, fullness)

    fun unseenMissedFlow() = occDao.unseenMissedFlow()
    fun unseenExpiredFlow() = sessionDao.unseenExpiredFlow()

    suspend fun markSummarySeen() {
        occDao.markAllSeen(); sessionDao.markAllSeen()
    }

    fun occurrenceRowsFlow(from: Long, to: Long) = occDao.inRangeFlow(from, to)
    fun sessionsFlow(from: Long, to: Long) = sessionDao.inRangeFlow(from, to)

    // ---------------------------------------------------------------- drinks

    suspend fun logDrink(
        type: DrinkType,
        amountMl: Int,
        hasCalories: Boolean,
        caffeineMg: Int? = null,
        customName: String? = null,
        source: String = "MANUAL",
        token: String = UUID.randomUUID().toString(),
        at: Long = clock.now(),
    ): Boolean = drinkDao.insertIgnore(
        DrinkLogEntity(
            type = type.name, customName = customName?.take(40), amountMl = amountMl.coerceIn(1, 5000),
            caffeineMg = caffeineMg?.coerceIn(0, 2000), hasCalories = hasCalories, timestamp = at, source = source, token = token,
        )
    ) != -1L

    suspend fun deleteDrink(id: Long) = drinkDao.delete(id)
    fun drinksFlow(from: Long, to: Long) = drinkDao.inRangeFlow(from, to)
    suspend fun drinks(from: Long, to: Long) = drinkDao.inRange(from, to)
    suspend fun lastDrinkAt(): Long? = drinkDao.lastTimestamp()

    // ---------------------------------------------------------------- privacy

    suspend fun deleteHistory() {
        db.withTransaction {
            drinkDao.clear()
            sessionDao.clearFinished()
            occDao.clear()
        }
    }

    suspend fun deleteAll() {
        db.withTransaction {
            drinkDao.clear(); sessionDao.clear(); occDao.clear()
            scheduleDao.clearAssignments(); scheduleDao.clearProfiles()
            db.schedule().overrides().forEach { db.schedule().deleteOverride(it.epochDay) }
        }
        settings.clear()
    }
}
