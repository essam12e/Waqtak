package com.waqtak.app.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

object AppLocale {
    fun current(): Locale = AppCompatDelegate.getApplicationLocales()[0] ?: Locale("ar")

    fun isArabic(): Boolean = current().language == "ar"

    /** Context whose resources follow the in-app language (services, receivers, notifications). */
    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(current())
        return context.createConfigurationContext(config)
    }
}
