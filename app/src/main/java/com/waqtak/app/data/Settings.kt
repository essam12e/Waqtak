package com.waqtak.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.waqtak.app.domain.MealDefaults
import com.waqtak.app.domain.MealType
import com.waqtak.app.domain.ScheduleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "waqtak_settings")

enum class Tone(val resName: String) {
    PULSE("tone_pulse"), CHIME("tone_chime"), RISE("tone_rise");

    companion object {
        fun parse(v: String?, fallback: Tone) = entries.firstOrNull { it.name == v } ?: fallback
    }
}

@kotlinx.serialization.Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    val scheduleMode: ScheduleMode = ScheduleMode.REGULAR,
    val hasReflux: Boolean = false,
    val hasFluidRestriction: Boolean = false,
    val hasMedicalPlan: Boolean = false,
    /** 0 = no goal. Optional and user-defined; with a fluid restriction it is the doctor's target. */
    val fluidGoalMl: Int = 0,
    val hydrationEnabled: Boolean = true,
    val hydrationIntervalMin: Int = 90,
    val defaultDrinkMl: Int = 250,
    val caffeineReminderEnabled: Boolean = false,
    val caffeineCutoffHours: Int = 8,
    val eatingWindowEnabled: Boolean = false,
    val eatingWindowStart: Int = 8 * 60,
    val eatingWindowEnd: Int = 20 * 60,
    val alarmVolume: Float = 1f,
    val vibrate: Boolean = true,
    val crescendo: Boolean = true,
    val startTone: Tone = Tone.PULSE,
    val endTone: Tone = Tone.CHIME,
    val durations: Map<MealType, Int> = MealType.entries.associateWith { MealDefaults.durationFor(it) },
    val bypassDnd: Boolean = false,
    val lastReconcileAt: Long = 0,
    val drinkHandledUpTo: Long = 0,
    val drinkSnoozeUntil: Long = 0,
    val caffeineNotifiedEpochDay: Long = -1,
) {
    fun duration(type: MealType) = durations[type] ?: MealDefaults.durationFor(type)
}

class SettingsRepository(private val context: Context) {
    private object K {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val scheduleMode = stringPreferencesKey("schedule_mode")
        val reflux = booleanPreferencesKey("reflux")
        val fluidRestriction = booleanPreferencesKey("fluid_restriction")
        val medicalPlan = booleanPreferencesKey("medical_plan")
        val fluidGoal = intPreferencesKey("fluid_goal_ml")
        val hydrationEnabled = booleanPreferencesKey("hydration_enabled")
        val hydrationInterval = intPreferencesKey("hydration_interval")
        val defaultDrink = intPreferencesKey("default_drink_ml")
        val caffeineReminder = booleanPreferencesKey("caffeine_reminder")
        val caffeineCutoff = intPreferencesKey("caffeine_cutoff_h")
        val eatingWindow = booleanPreferencesKey("eating_window")
        val eatingStart = intPreferencesKey("eating_start")
        val eatingEnd = intPreferencesKey("eating_end")
        val volume = floatPreferencesKey("alarm_volume")
        val vibrate = booleanPreferencesKey("vibrate")
        val crescendo = booleanPreferencesKey("crescendo")
        val startTone = stringPreferencesKey("start_tone")
        val endTone = stringPreferencesKey("end_tone")
        fun duration(t: MealType) = intPreferencesKey("duration_${t.name}")
        val bypassDnd = booleanPreferencesKey("bypass_dnd")
        val lastReconcile = longPreferencesKey("last_reconcile")
        val drinkHandled = longPreferencesKey("drink_handled")
        val drinkSnooze = longPreferencesKey("drink_snooze")
        val caffeineDay = longPreferencesKey("caffeine_day")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
            scheduleMode = ScheduleMode.parse(this[K.scheduleMode]),
            hasReflux = this[K.reflux] ?: d.hasReflux,
            hasFluidRestriction = this[K.fluidRestriction] ?: d.hasFluidRestriction,
            hasMedicalPlan = this[K.medicalPlan] ?: d.hasMedicalPlan,
            fluidGoalMl = this[K.fluidGoal] ?: d.fluidGoalMl,
            hydrationEnabled = this[K.hydrationEnabled] ?: d.hydrationEnabled,
            hydrationIntervalMin = this[K.hydrationInterval] ?: d.hydrationIntervalMin,
            defaultDrinkMl = this[K.defaultDrink] ?: d.defaultDrinkMl,
            caffeineReminderEnabled = this[K.caffeineReminder] ?: d.caffeineReminderEnabled,
            caffeineCutoffHours = this[K.caffeineCutoff] ?: d.caffeineCutoffHours,
            eatingWindowEnabled = this[K.eatingWindow] ?: d.eatingWindowEnabled,
            eatingWindowStart = this[K.eatingStart] ?: d.eatingWindowStart,
            eatingWindowEnd = this[K.eatingEnd] ?: d.eatingWindowEnd,
            alarmVolume = this[K.volume] ?: d.alarmVolume,
            vibrate = this[K.vibrate] ?: d.vibrate,
            crescendo = this[K.crescendo] ?: d.crescendo,
            startTone = Tone.parse(this[K.startTone], d.startTone),
            endTone = Tone.parse(this[K.endTone], d.endTone),
            durations = MealType.entries.associateWith { this[K.duration(it)] ?: MealDefaults.durationFor(it) },
            bypassDnd = this[K.bypassDnd] ?: d.bypassDnd,
            lastReconcileAt = this[K.lastReconcile] ?: 0,
            drinkHandledUpTo = this[K.drinkHandled] ?: 0,
            drinkSnoozeUntil = this[K.drinkSnooze] ?: 0,
            caffeineNotifiedEpochDay = this[K.caffeineDay] ?: -1,
        )
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun get(): AppSettings = flow.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = block(prefs.toSettings())
            write(prefs, s)
        }
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.onboardingDone] = s.onboardingDone
        p[K.scheduleMode] = s.scheduleMode.name
        p[K.reflux] = s.hasReflux
        p[K.fluidRestriction] = s.hasFluidRestriction
        p[K.medicalPlan] = s.hasMedicalPlan
        p[K.fluidGoal] = s.fluidGoalMl
        p[K.hydrationEnabled] = s.hydrationEnabled
        p[K.hydrationInterval] = s.hydrationIntervalMin
        p[K.defaultDrink] = s.defaultDrinkMl
        p[K.caffeineReminder] = s.caffeineReminderEnabled
        p[K.caffeineCutoff] = s.caffeineCutoffHours
        p[K.eatingWindow] = s.eatingWindowEnabled
        p[K.eatingStart] = s.eatingWindowStart
        p[K.eatingEnd] = s.eatingWindowEnd
        p[K.volume] = s.alarmVolume
        p[K.vibrate] = s.vibrate
        p[K.crescendo] = s.crescendo
        p[K.startTone] = s.startTone.name
        p[K.endTone] = s.endTone.name
        s.durations.forEach { (t, v) -> p[K.duration(t)] = v }
        p[K.bypassDnd] = s.bypassDnd
        p[K.lastReconcile] = s.lastReconcileAt
        p[K.drinkHandled] = s.drinkHandledUpTo
        p[K.drinkSnooze] = s.drinkSnoozeUntil
        p[K.caffeineDay] = s.caffeineNotifiedEpochDay
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
