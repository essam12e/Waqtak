package com.waqtak.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.waqtak.app.util.AppLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocaleAndStringsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun switchingLanguageChangesAlarmTexts() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        val ar = AppLocale.wrap(ctx)
        assertEquals("موافق، بدأت الأكل", ar.getString(R.string.action_start_eating))
        assertEquals("انتهت المدة المحددة للوجبة. خذ لحظة وقيّم شعورك بالشبع.", ar.getString(R.string.alarm_end_text))
        assertEquals("وقت مناسب للشرب، اختر ما يناسبك.", ar.getString(R.string.drink_reminder_text))
        assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, ar.resources.configuration.layoutDirection)

        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        val en = AppLocale.wrap(ctx)
        assertEquals("OK, I started eating", en.getString(R.string.action_start_eating))
        assertEquals(android.view.View.LAYOUT_DIRECTION_LTR, en.resources.configuration.layoutDirection)
        assertEquals("موافق، شربت 250 مل ماء", ar.getString(R.string.action_drank_amount, 250))
    }
}
