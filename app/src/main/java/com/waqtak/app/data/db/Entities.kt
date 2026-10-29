package com.waqtak.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val wakeMinute: Int,
    val sleepMinute: Int,
)

@Serializable
@Entity(
    tableName = "meal_plans",
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("profileId")],
)
data class MealPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val type: String,
    val label: String,
    val minuteOfDay: Int,
    val durationMin: Int,
    val enabled: Boolean = true,
    /** true once the user changed this time by hand; redistribution never overwrites it silently. */
    val userEdited: Boolean = false,
    /** occurrences before this instant are ignored, so saving a plan never rings for the past. */
    val effectiveFrom: Long,
)

@Serializable
@Entity(tableName = "weekday_assignments")
data class WeekdayAssignmentEntity(
    @PrimaryKey val isoDay: Int,
    val profileId: Long,
)

@Serializable
@Entity(tableName = "date_overrides")
data class DateOverrideEntity(
    @PrimaryKey val epochDay: Long,
    val profileId: Long,
)

/** State of a concrete meal occurrence; a row exists only once something happened to it. */
@Serializable
@Entity(tableName = "meal_occurrences", indices = [Index("scheduledAt"), Index("status")])
data class MealOccurrenceEntity(
    @PrimaryKey val key: String,
    val planId: Long,
    val mealType: String,
    val label: String,
    val scheduledAt: Long,
    val durationMin: Int,
    val status: String,
    val snoozeUntil: Long? = null,
    val updatedAt: Long,
    /** false for missed items not yet shown in the "while you were away" summary. */
    val seen: Boolean = true,
)

@Serializable
@Entity(
    tableName = "meal_sessions",
    indices = [Index(value = ["occurrenceKey"], unique = true), Index("state"), Index("startWall")],
)
data class MealSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceKey: String,
    val mealType: String,
    val label: String,
    val startWall: Long,
    val startElapsed: Long,
    val bootCount: Int,
    val plannedMs: Long,
    val extraMs: Long = 0,
    val state: String,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val fullness: Int? = null,
    val seen: Boolean = true,
) {
    val totalMs: Long get() = plannedMs + extraMs
}

@Serializable
@Entity(
    tableName = "drink_logs",
    indices = [Index(value = ["token"], unique = true), Index("timestamp")],
)
data class DrinkLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val customName: String? = null,
    val amountMl: Int,
    val caffeineMg: Int? = null,
    val hasCalories: Boolean,
    val timestamp: Long,
    val source: String,
    /** idempotency token: a double tap or a duplicated broadcast inserts only once. */
    val token: String,
)
