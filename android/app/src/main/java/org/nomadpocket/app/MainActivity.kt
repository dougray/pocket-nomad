package org.nomadpocket.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.nomadpocket.app.ui.NomadTheme
import org.nomadpocket.app.ui.RootScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Dark mode saves battery on OLED screens, so it is on by default.
        val startDark = prefs.getBoolean("forceDark", true)
        applyBars(startDark)
        // Debug builds can open straight to a screen: adb shell am start ... -e screen tool:water
        val launch = if (BuildConfig.DEBUG && savedInstanceState == null) intent.getStringExtra("screen") else null
        DemoData.load(this, launch)
        setContent {
            var forceDark by remember { mutableStateOf(startDark) }
            NomadTheme(forceDark) {
                RootScreen(
                    forceDark = forceDark,
                    onForceDark = {
                        forceDark = it
                        prefs.edit().putBoolean("forceDark", it).apply()
                        applyBars(it)
                    },
                    launchScreen = launch,
                )
            }
        }
    }

    private fun applyBars(forceDark: Boolean) {
        if (forceDark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
        } else {
            enableEdgeToEdge()
        }
    }
}
