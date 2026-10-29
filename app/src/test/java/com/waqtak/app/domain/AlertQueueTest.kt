package com.waqtak.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertQueueTest {
    private fun a(id: String, kind: AlertKind, at: Long) = Alert(id, kind, MealType.LUNCH, "", at, id)

    @Test
    fun duplicateDeliveryIsIgnored() {
        val q = AlertQueue()
        assertTrue(q.add(a("start:1", AlertKind.MEAL_START, 10)))
        assertFalse(q.add(a("start:1", AlertKind.MEAL_START, 10)))
        assertEquals(1, q.all.size)
    }

    @Test
    fun mealEndHasPriorityOverMealStart_oneSoundAtATime() {
        val q = AlertQueue()
        q.add(a("start:1", AlertKind.MEAL_START, 10))
        q.add(a("end:5", AlertKind.MEAL_END, 20))
        assertEquals("end:5", q.top!!.id)
        q.remove("end:5")
        assertEquals("start:1", q.top!!.id)
    }

    @Test
    fun simultaneousMealsPlayInScheduledOrder() {
        val q = AlertQueue()
        q.add(a("start:2", AlertKind.MEAL_START, 20))
        q.add(a("start:1", AlertKind.MEAL_START, 10))
        assertEquals(listOf("start:1", "start:2"), q.all.map { it.id })
    }
}
