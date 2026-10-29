package com.waqtak.app.alarm

import com.waqtak.app.domain.Alert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-process view of what is ringing right now (top first), for the alarm screen. */
object RingingHub {
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts

    /** ids resolved in this process; lets the alarm screen close even without the service. */
    private val _resolved = MutableStateFlow<Map<String, Long>>(emptyMap())
    val resolved: StateFlow<Map<String, Long>> = _resolved

    internal fun publish(list: List<Alert>) {
        _alerts.value = list
    }

    fun markResolved(id: String, at: Long = System.currentTimeMillis()) {
        _resolved.value = _resolved.value + (id to at)
    }

    /** A re-ring of the same meal after snooze has a later scheduled time, so it is not considered resolved. */
    fun isResolved(a: Alert, map: Map<String, Long> = _resolved.value): Boolean =
        map[a.id]?.let { it >= a.scheduledAt - 60_000 } == true
}
