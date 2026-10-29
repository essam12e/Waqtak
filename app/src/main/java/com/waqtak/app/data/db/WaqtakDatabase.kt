package com.waqtak.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        MealPlanEntity::class,
        WeekdayAssignmentEntity::class,
        DateOverrideEntity::class,
        MealOccurrenceEntity::class,
        MealSessionEntity::class,
        DrinkLogEntity::class,
        TimeOverrideEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class WaqtakDatabase : RoomDatabase() {
    abstract fun schedule(): ScheduleDao
    abstract fun occurrences(): OccurrenceDao
    abstract fun sessions(): SessionDao
    abstract fun drinks(): DrinkDao

    companion object {
        /** 1.0.0 -> 1.1.0: repeat days per meal, and "today only" time changes. Keeps all user data. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meal_plans ADD COLUMN daysMask INTEGER NOT NULL DEFAULT 127")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `time_overrides` (`key` TEXT NOT NULL, `planId` INTEGER NOT NULL, " +
                        "`atMillis` INTEGER NOT NULL, `effectiveFrom` INTEGER NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        fun build(context: Context, inMemory: Boolean = false): WaqtakDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, WaqtakDatabase::class.java).allowMainThreadQueries()
            } else {
                Room.databaseBuilder(context, WaqtakDatabase::class.java, "waqtak.db")
            }
            return builder.addMigrations(MIGRATION_1_2).build()
        }
    }
}
