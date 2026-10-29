package com.waqtak.app.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.data.db.WaqtakDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A 1.0.0 database (schema 1) with user data opens in 1.1.0 with everything kept. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val v1Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `wakeMinute` INTEGER NOT NULL, `sleepMinute` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `meal_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profileId` INTEGER NOT NULL, `type` TEXT NOT NULL, `label` TEXT NOT NULL, `minuteOfDay` INTEGER NOT NULL, `durationMin` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `userEdited` INTEGER NOT NULL, `effectiveFrom` INTEGER NOT NULL, FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_meal_plans_profileId` ON `meal_plans` (`profileId`)",
        "CREATE TABLE IF NOT EXISTS `weekday_assignments` (`isoDay` INTEGER NOT NULL, `profileId` INTEGER NOT NULL, PRIMARY KEY(`isoDay`))",
        "CREATE TABLE IF NOT EXISTS `date_overrides` (`epochDay` INTEGER NOT NULL, `profileId` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
        "CREATE TABLE IF NOT EXISTS `meal_occurrences` (`key` TEXT NOT NULL, `planId` INTEGER NOT NULL, `mealType` TEXT NOT NULL, `label` TEXT NOT NULL, `scheduledAt` INTEGER NOT NULL, `durationMin` INTEGER NOT NULL, `status` TEXT NOT NULL, `snoozeUntil` INTEGER, `updatedAt` INTEGER NOT NULL, `seen` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        "CREATE INDEX IF NOT EXISTS `index_meal_occurrences_scheduledAt` ON `meal_occurrences` (`scheduledAt`)",
        "CREATE INDEX IF NOT EXISTS `index_meal_occurrences_status` ON `meal_occurrences` (`status`)",
        "CREATE TABLE IF NOT EXISTS `meal_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `occurrenceKey` TEXT NOT NULL, `mealType` TEXT NOT NULL, `label` TEXT NOT NULL, `startWall` INTEGER NOT NULL, `startElapsed` INTEGER NOT NULL, `bootCount` INTEGER NOT NULL, `plannedMs` INTEGER NOT NULL, `extraMs` INTEGER NOT NULL, `state` TEXT NOT NULL, `endedAt` INTEGER, `endReason` TEXT, `fullness` INTEGER, `seen` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_meal_sessions_occurrenceKey` ON `meal_sessions` (`occurrenceKey`)",
        "CREATE INDEX IF NOT EXISTS `index_meal_sessions_state` ON `meal_sessions` (`state`)",
        "CREATE INDEX IF NOT EXISTS `index_meal_sessions_startWall` ON `meal_sessions` (`startWall`)",
        "CREATE TABLE IF NOT EXISTS `drink_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `customName` TEXT, `amountMl` INTEGER NOT NULL, `caffeineMg` INTEGER, `hasCalories` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `source` TEXT NOT NULL, `token` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_drink_logs_token` ON `drink_logs` (`token`)",
        "CREATE INDEX IF NOT EXISTS `index_drink_logs_timestamp` ON `drink_logs` (`timestamp`)",
    )

    @Test
    fun version1DataSurvivesTheUpgrade() = runBlocking<Unit> {
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    v1Schema.forEach { db.execSQL(it) }
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO profiles (id, name, kind, wakeMinute, sleepMinute) VALUES (1, 'x', 'WORK', 420, 1380)")
            execSQL("INSERT INTO meal_plans (id, profileId, type, label, minuteOfDay, durationMin, enabled, userEdited, effectiveFrom) VALUES (5, 1, 'LUNCH', '', 810, 30, 1, 1, 0)")
            execSQL("INSERT INTO drink_logs (type, amountMl, hasCalories, timestamp, source, token) VALUES ('WATER', 250, 0, 1000, 'MANUAL', 't1')")
        }
        helper.close()

        val db = Room.databaseBuilder(ctx, WaqtakDatabase::class.java, name)
            .addMigrations(WaqtakDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        val plan = db.schedule().plans().single()
        assertEquals(810, plan.minuteOfDay) // user's own time kept
        assertEquals(30, plan.durationMin)
        assertEquals(127, plan.daysMask)    // every day, as before
        assertEquals(1, db.drinks().all().size)
        assertEquals(0, db.schedule().timeOverrides().size)
        db.close()
    }
}
