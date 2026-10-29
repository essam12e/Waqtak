package com.waqtak.app

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.util.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocaleAndStringsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun arabicIsDefault_andAlarmTextsAreArabicRtl() {
        val ar = AppLocale.wrap(ctx)
        assertEquals("ar", AppLocale.current().language)
        assertEquals("موافق، بدأت الأكل", ar.getString(R.string.action_start_eating))
        assertEquals("انتهت المدة المحددة للوجبة. خذ لحظة وقيّم شعورك بالشبع.", ar.getString(R.string.alarm_end_text))
        assertEquals("وقت مناسب للشرب، اختر ما يناسبك.", ar.getString(R.string.drink_reminder_text))
        assertEquals(View.LAYOUT_DIRECTION_RTL, ar.resources.configuration.layoutDirection)
        val drank = ar.getString(R.string.action_drank_amount, 250)
        assertTrue(drank, drank.startsWith("موافق، شربت") && (drank.contains("250") || drank.contains("٢٥٠")))
    }

    @Test
    fun englishResourcesAreLtrAndComplete() {
        val en = AppLocale.wrap(ctx, Locale.ENGLISH)
        assertEquals("OK, I started eating", en.getString(R.string.action_start_eating))
        assertEquals("OK, I drank 250 ml of water", en.getString(R.string.action_drank_amount, 250))
        assertEquals(View.LAYOUT_DIRECTION_LTR, en.resources.configuration.layoutDirection)
    }
}
