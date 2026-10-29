package com.waqtak.app

import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.waqtak.app.alarm.Reconciler
import com.waqtak.app.ui.WaqtakAppUi
import com.waqtak.app.ui.theme.WaqtakTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ROUTE = "route"
    }

    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            // Arabic is the default language. AppCompat requires this call after Activity.onCreate;
            // the activity is recreated with Arabic resources (RTL). The user can switch any time.
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
        }
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            WaqtakTheme {
                WaqtakAppUi(pendingRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    override fun onResume() {
        super.onResume()
        // Catches anything that cleared our alarms while we were not running (e.g. force stop).
        val app = applicationContext
        lifecycleScope.launch { app.graph.scope.launch { Reconciler.reconcile(app, "resume") } }
    }
}
