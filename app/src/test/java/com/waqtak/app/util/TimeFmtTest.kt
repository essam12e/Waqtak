package com.waqtak.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TimeFmtTest {
    private val ar = Locale("ar")
    private fun latin(s: String) = s.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")

    @Test
    fun twelveHourInArabicAndEnglish() {
        assertEquals("12:00 ص", latin(TimeFmt.clock(0, ar)))        // midnight
        assertEquals("12:00 م", latin(TimeFmt.clock(12 * 60, ar)))  // noon
        assertEquals("1:00 م", latin(TimeFmt.clock(13 * 60, ar)))
        assertEquals("6:00 م", latin(TimeFmt.clock(18 * 60, ar)))
        assertEquals("8:05 ص", latin(TimeFmt.clock(8 * 60 + 5, ar)))
        assertEquals("1:00 PM", TimeFmt.clock(13 * 60, Locale.ENGLISH))
        assertEquals("12:00 AM", TimeFmt.clock(0, Locale.ENGLISH))
        assertEquals("11:59 PM", TimeFmt.clock(23 * 60 + 59, Locale.ENGLISH))
    }
}
