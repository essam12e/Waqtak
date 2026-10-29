package com.waqtak.app

import android.content.Context
import android.content.Intent
import com.waqtak.app.util.AppLocale
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

    // Resources always follow the in-app language (Arabic by default), on every Android version.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
