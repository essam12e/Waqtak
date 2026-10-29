package com.waqtak.app.domain

enum class AlertKind(val priority: Int) { MEAL_END(3), MEAL_START(2), TEST(1) }

data class Alert(
    val id: String,
    val kind: AlertKind,
    val mealType: MealType?,
    val label: String,
    val scheduledAt: Long,
    /** occurrence key for MEAL_START, session id for MEAL_END */
    val ref: String,
    val durationMin: Int = 0,
)

/**
 * One sound at a time. The highest-priority alert plays; others wait. Duplicate
 * deliveries of the same alert id are ignored, so repeated broadcasts never double the sound.
 */
class AlertQueue {
    private val items = LinkedHashMap<String, Alert>()

    val top: Alert?
        get() = items.values.sortedWith(compareByDescending<Alert> { it.kind.priority }.thenBy { it.scheduledAt }).firstOrNull()

    val all: List<Alert>
        get() = items.values.sortedWith(compareByDescending<Alert> { it.kind.priority }.thenBy { it.scheduledAt })

    fun add(alert: Alert): Boolean {
        if (items.containsKey(alert.id)) return false
        items[alert.id] = alert
        return true
    }

    fun remove(id: String): Alert? = items.remove(id)

    fun contains(id: String) = items.containsKey(id)

    fun isEmpty() = items.isEmpty()

    fun clear() = items.clear()
}
