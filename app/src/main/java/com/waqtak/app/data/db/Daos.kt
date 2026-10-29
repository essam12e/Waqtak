package com.waqtak.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun profilesFlow(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun profiles(): List<ProfileEntity>

    @Insert
    suspend fun insertProfile(p: ProfileEntity): Long

    @Update
    suspend fun updateProfile(p: ProfileEntity)

    @Delete
    suspend fun deleteProfile(p: ProfileEntity)

    @Query("SELECT * FROM meal_plans ORDER BY profileId, minuteOfDay")
    fun plansFlow(): Flow<List<MealPlanEntity>>

    @Query("SELECT * FROM meal_plans")
    suspend fun plans(): List<MealPlanEntity>

    @Query("SELECT * FROM meal_plans WHERE id = :id")
    suspend fun plan(id: Long): MealPlanEntity?

    @Insert
    suspend fun insertPlan(p: MealPlanEntity): Long

    @Update
    suspend fun updatePlan(p: MealPlanEntity)

    @Query("DELETE FROM meal_plans WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("SELECT * FROM weekday_assignments")
    fun assignmentsFlow(): Flow<List<WeekdayAssignmentEntity>>

    @Query("SELECT * FROM weekday_assignments")
    suspend fun assignments(): List<WeekdayAssignmentEntity>

    @Upsert
    suspend fun upsertAssignment(a: WeekdayAssignmentEntity)

    @Query("SELECT * FROM date_overrides")
    fun overridesFlow(): Flow<List<DateOverrideEntity>>

    @Query("SELECT * FROM date_overrides")
    suspend fun overrides(): List<DateOverrideEntity>

    @Upsert
    suspend fun upsertOverride(o: DateOverrideEntity)

    @Query("DELETE FROM date_overrides WHERE epochDay = :epochDay")
    suspend fun deleteOverride(epochDay: Long)

    @Query("DELETE FROM date_overrides WHERE epochDay < :beforeEpochDay")
    suspend fun pruneOverrides(beforeEpochDay: Long)

    @Query("DELETE FROM weekday_assignments")
    suspend fun clearAssignments()

    @Query("DELETE FROM profiles")
    suspend fun clearProfiles()

    @Query("SELECT * FROM time_overrides")
    fun timeOverridesFlow(): Flow<List<TimeOverrideEntity>>

    @Query("SELECT * FROM time_overrides")
    suspend fun timeOverrides(): List<TimeOverrideEntity>

    @Upsert
    suspend fun upsertTimeOverride(o: TimeOverrideEntity)

    @Query("DELETE FROM time_overrides WHERE atMillis < :before")
    suspend fun pruneTimeOverrides(before: Long)

    @Query("DELETE FROM time_overrides")
    suspend fun clearTimeOverrides()
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM meal_occurrences WHERE `key` = :key")
    suspend fun get(key: String): MealOccurrenceEntity?

    @Query("SELECT * FROM meal_occurrences WHERE scheduledAt >= :from AND scheduledAt < :to")
    suspend fun inRange(from: Long, to: Long): List<MealOccurrenceEntity>

    @Query("SELECT * FROM meal_occurrences WHERE scheduledAt >= :from AND scheduledAt < :to")
    fun inRangeFlow(from: Long, to: Long): Flow<List<MealOccurrenceEntity>>

    @Query("SELECT * FROM meal_occurrences WHERE status = 'SNOOZED'")
    suspend fun snoozed(): List<MealOccurrenceEntity>

    @Query("SELECT * FROM meal_occurrences WHERE status = 'RINGING'")
    suspend fun ringing(): List<MealOccurrenceEntity>

    @Query("SELECT * FROM meal_occurrences WHERE status = 'MISSED' AND seen = 0 ORDER BY scheduledAt")
    fun unseenMissedFlow(): Flow<List<MealOccurrenceEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(o: MealOccurrenceEntity): Long

    /** Conditional state transition; returns 1 only for the first caller. */
    @Query("UPDATE meal_occurrences SET status = :to, updatedAt = :now WHERE `key` = :key AND status IN (:from)")
    suspend fun transition(key: String, from: List<String>, to: String, now: Long): Int

    @Query(
        "UPDATE meal_occurrences SET status = 'SNOOZED', snoozeUntil = :until, updatedAt = :now " +
            "WHERE `key` = :key AND status IN ('RINGING','SNOOZED')"
    )
    suspend fun snooze(key: String, until: Long, now: Long): Int

    @Query("UPDATE meal_occurrences SET seen = 1 WHERE seen = 0")
    suspend fun markAllSeen()

    @Query("DELETE FROM meal_occurrences")
    suspend fun clear()

    @Query("SELECT * FROM meal_occurrences ORDER BY scheduledAt")
    suspend fun all(): List<MealOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MealOccurrenceEntity>)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(s: MealSessionEntity): Long

    @Query("SELECT * FROM meal_sessions WHERE id = :id")
    suspend fun get(id: Long): MealSessionEntity?

    @Query("SELECT * FROM meal_sessions WHERE state IN ('ACTIVE','END_RINGING') ORDER BY startWall DESC")
    suspend fun open(): List<MealSessionEntity>

    @Query("SELECT * FROM meal_sessions WHERE state IN ('ACTIVE','END_RINGING') ORDER BY startWall DESC LIMIT 1")
    fun currentFlow(): Flow<MealSessionEntity?>

    @Query("SELECT * FROM meal_sessions WHERE startWall >= :from AND startWall < :to ORDER BY startWall")
    fun inRangeFlow(from: Long, to: Long): Flow<List<MealSessionEntity>>

    @Query("SELECT * FROM meal_sessions WHERE endReason = 'EXPIRED_UNATTENDED' AND seen = 0")
    fun unseenExpiredFlow(): Flow<List<MealSessionEntity>>

    @Query("UPDATE meal_sessions SET state = :to WHERE id = :id AND state IN (:from)")
    suspend fun transition(id: Long, from: List<String>, to: String): Int

    @Query(
        "UPDATE meal_sessions SET state = 'FINISHED', endedAt = :now, endReason = :reason, " +
            "fullness = COALESCE(:fullness, fullness), seen = :seen WHERE id = :id AND state IN ('ACTIVE','END_RINGING')"
    )
    suspend fun finish(id: Long, now: Long, reason: String, fullness: Int?, seen: Boolean): Int

    @Query(
        "UPDATE meal_sessions SET state = 'ACTIVE', extraMs = extraMs + :addMs " +
            "WHERE id = :id AND state IN ('ACTIVE','END_RINGING')"
    )
    suspend fun extend(id: Long, addMs: Long): Int

    @Query("UPDATE meal_sessions SET plannedMs = :plannedMs WHERE id = :id AND state = 'ACTIVE'")
    suspend fun setPlanned(id: Long, plannedMs: Long): Int

    @Query("UPDATE meal_sessions SET fullness = :fullness WHERE id = :id")
    suspend fun setFullness(id: Long, fullness: Int?)

    @Query("UPDATE meal_sessions SET seen = 1 WHERE seen = 0")
    suspend fun markAllSeen()

    @Query("DELETE FROM meal_sessions WHERE state = 'FINISHED'")
    suspend fun clearFinished()

    @Query("DELETE FROM meal_sessions")
    suspend fun clear()

    @Query("SELECT * FROM meal_sessions ORDER BY startWall")
    suspend fun all(): List<MealSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MealSessionEntity>)
}

@Dao
interface DrinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(d: DrinkLogEntity): Long

    @Query("SELECT * FROM drink_logs WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    fun inRangeFlow(from: Long, to: Long): Flow<List<DrinkLogEntity>>

    @Query("SELECT * FROM drink_logs WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    suspend fun inRange(from: Long, to: Long): List<DrinkLogEntity>

    @Query("SELECT MAX(timestamp) FROM drink_logs")
    suspend fun lastTimestamp(): Long?

    @Query("DELETE FROM drink_logs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM drink_logs")
    suspend fun clear()

    @Query("SELECT * FROM drink_logs ORDER BY timestamp")
    suspend fun all(): List<DrinkLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DrinkLogEntity>)
}
