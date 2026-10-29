package com.waqtak.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        MealPlanEntity::class,
        WeekdayAssignmentEntity::class,
        DateOverrideEntity::class,
        MealOccurrenceEntity::class,
        MealSessionEntity::class,
        DrinkLogEntity::class,
    ],
    version = 1,
    // v1: no migrations yet. Enable schema export (with a per-build location) when adding version 2.
    exportSchema = false,
)
abstract class WaqtakDatabase : RoomDatabase() {
    abstract fun schedule(): ScheduleDao
    abstract fun occurrences(): OccurrenceDao
    abstract fun sessions(): SessionDao
    abstract fun drinks(): DrinkDao

    companion object {
        fun build(context: Context, inMemory: Boolean = false): WaqtakDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, WaqtakDatabase::class.java).allowMainThreadQueries()
            } else {
                Room.databaseBuilder(context, WaqtakDatabase::class.java, "waqtak.db")
            }
            return builder.build()
        }
    }
}
