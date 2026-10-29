package com.waqtak.app.data.backup

import androidx.room.withTransaction
import com.waqtak.app.data.AppSettings
import com.waqtak.app.data.WaqtakRepository
import com.waqtak.app.data.db.DateOverrideEntity
import com.waqtak.app.data.db.DrinkLogEntity
import com.waqtak.app.data.db.MealOccurrenceEntity
import com.waqtak.app.data.db.MealPlanEntity
import com.waqtak.app.data.db.MealSessionEntity
import com.waqtak.app.data.db.ProfileEntity
import com.waqtak.app.data.db.TimeOverrideEntity
import com.waqtak.app.data.db.WeekdayAssignmentEntity
import com.waqtak.app.domain.DrinkType
import com.waqtak.app.domain.EndReason
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.OccurrenceStatus
import com.waqtak.app.domain.ProfileKind
import com.waqtak.app.domain.SessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long,
    val settings: AppSettings,
    val profiles: List<ProfileEntity>,
    val plans: List<MealPlanEntity>,
    val assignments: List<WeekdayAssignmentEntity>,
    val overrides: List<DateOverrideEntity>,
    val occurrences: List<MealOccurrenceEntity>,
    val sessions: List<MealSessionEntity>,
    val drinks: List<DrinkLogEntity>,
    val timeOverrides: List<TimeOverrideEntity> = emptyList(),
) {
    companion object {
        const val FORMAT = "waqtak-backup"
        /** 2 = app 1.1.0 (repeat days, "this day only" times); version 1 files are still accepted */
        const val VERSION = 2
    }
}

class BackupException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_JSON, WRONG_FORMAT, NEWER_VERSION, INVALID_DATA, TOO_LARGE }
}

object BackupCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_ROWS = 200_000

    fun encode(b: BackupFile): String = json.encodeToString(BackupFile.serializer(), b)

    /** Parses and validates; throws [BackupException] with a user-presentable reason. */
    fun decode(text: String): BackupFile {
        if (text.length > MAX_BYTES) throw BackupException(BackupException.Reason.TOO_LARGE, "file too large")
        val b = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            if (!text.trimStart().startsWith("{")) throw BackupException(BackupException.Reason.NOT_JSON, "not json")
            throw BackupException(BackupException.Reason.WRONG_FORMAT, e.message ?: "parse error")
        }
        validate(b)
        return b
    }

    fun validate(b: BackupFile) {
        fun bad(msg: String): Nothing = throw BackupException(BackupException.Reason.INVALID_DATA, msg)
        if (b.format != BackupFile.FORMAT) throw BackupException(BackupException.Reason.WRONG_FORMAT, "format")
        if (b.version > BackupFile.VERSION) throw BackupException(BackupException.Reason.NEWER_VERSION, "version ${b.version}")
        if (b.version < 1) bad("version")
        val rows = b.profiles.size + b.plans.size + b.occurrences.size + b.sessions.size + b.drinks.size
        if (rows > MAX_ROWS) throw BackupException(BackupException.Reason.TOO_LARGE, "rows")
        val minute = 0..1439
        val profileIds = b.profiles.map { it.id }.toSet()
        if (profileIds.size != b.profiles.size) bad("duplicate profile ids")
        b.profiles.forEach {
            if (it.wakeMinute !in minute || it.sleepMinute !in minute) bad("profile time")
            if (ProfileKind.entries.none { k -> k.name == it.kind }) bad("profile kind")
            if (it.name.length > 60) bad("profile name")
        }
        if (b.plans.map { it.id }.toSet().size != b.plans.size) bad("duplicate plan ids")
        b.plans.forEach {
            if (it.profileId !in profileIds) bad("plan profile")
            if (it.minuteOfDay !in minute) bad("plan time")
            if (it.durationMin !in 1..240) bad("plan duration")
            if (MealType.entries.none { t -> t.name == it.type }) bad("plan type")
            if (it.label.length > 60) bad("plan label")
            if (it.daysMask !in 0..127) bad("plan days")
        }
        b.assignments.forEach { if (it.isoDay !in 1..7 || it.profileId !in profileIds) bad("assignment") }
        b.overrides.forEach { if (it.profileId !in profileIds) bad("override") }
        b.occurrences.forEach {
            if (OccurrenceStatus.entries.none { s -> s.name == it.status }) bad("occurrence status")
            if (MealType.entries.none { t -> t.name == it.mealType }) bad("occurrence type")
        }
        b.sessions.forEach {
            if (SessionState.entries.none { s -> s.name == it.state }) bad("session state")
            if (it.plannedMs !in 0..(6 * 3_600_000L) || it.extraMs !in 0..(6 * 3_600_000L)) bad("session duration")
        }
        if (b.drinks.map { it.token }.toSet().size != b.drinks.size) bad("duplicate drink tokens")
        b.drinks.forEach {
            if (it.amountMl !in 1..5000) bad("drink amount")
            if (DrinkType.entries.none { t -> t.name == it.type }) bad("drink type")
            if (it.caffeineMg != null && it.caffeineMg !in 0..2000) bad("caffeine")
        }
        val s = b.settings
        if (s.hydrationIntervalMin !in 15..600 || s.defaultDrinkMl !in 1..5000 || s.fluidGoalMl !in 0..10000) bad("settings")
        if (s.durations.values.any { it !in 1..240 } || s.alarmVolume !in 0f..1f) bad("settings")
        if (s.drinkStart !in 0..1439 || s.drinkEnd !in 0..1439 || s.drinkTimes.any { it !in 0..1439 } || s.drinkDaysMask !in 0..127) bad("settings")
    }
}

class BackupManager(private val repo: WaqtakRepository) {
    suspend fun export(): String {
        val db = repo.db
        val b = BackupFile(
            exportedAt = repo.clock.now(),
            settings = repo.settings.get(),
            profiles = db.schedule().profiles(),
            plans = db.schedule().plans(),
            assignments = db.schedule().assignments(),
            overrides = db.schedule().overrides(),
            occurrences = db.occurrences().all(),
            sessions = db.sessions().all(),
            drinks = db.drinks().all(),
            timeOverrides = db.schedule().timeOverrides(),
        )
        return BackupCodec.encode(b)
    }

    /** Replaces all local data with the backup. Live alarm states are neutralised so nothing rings from old data. */
    suspend fun import(text: String) {
        val b = BackupCodec.decode(text)
        val now = repo.clock.now()
        val db = repo.db
        db.withTransaction {
            db.drinks().clear(); db.sessions().clear(); db.occurrences().clear()
            db.schedule().clearAssignments()
            db.schedule().overrides().forEach { db.schedule().deleteOverride(it.epochDay) }
            db.schedule().clearProfiles()
            db.schedule().clearTimeOverrides()
            b.profiles.forEach { db.schedule().insertProfile(it) }
            b.plans.forEach { db.schedule().insertPlan(it.copy(effectiveFrom = maxOf(it.effectiveFrom, now))) }
            b.assignments.forEach { db.schedule().upsertAssignment(it) }
            b.overrides.forEach { db.schedule().upsertOverride(it) }
            db.occurrences().insertAll(b.occurrences.map {
                if (it.status == OccurrenceStatus.RINGING.name || it.status == OccurrenceStatus.SNOOZED.name) {
                    it.copy(status = OccurrenceStatus.MISSED.name, snoozeUntil = null, seen = true)
                } else it
            })
            db.sessions().insertAll(b.sessions.map {
                if (it.state != SessionState.FINISHED.name) {
                    it.copy(state = SessionState.FINISHED.name, endedAt = now, endReason = EndReason.EXPIRED_UNATTENDED.name, seen = true)
                } else it
            })
            db.drinks().insertAll(b.drinks)
            b.timeOverrides.filter { o -> b.plans.any { it.id == o.planId } }.forEach { db.schedule().upsertTimeOverride(it) }
        }
        repo.settings.update {
            b.settings.copy(onboardingDone = true, introDone = true, lastReconcileAt = now, drinkHandledUpTo = now, drinkSnoozeUntil = 0)
        }
    }
}
