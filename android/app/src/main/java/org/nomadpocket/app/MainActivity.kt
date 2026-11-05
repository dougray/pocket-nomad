package org.nomadpocket.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import org.nomadpocket.app.ask.ModelStore
import org.nomadpocket.app.library.Importer
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
        if (BuildConfig.DEBUG && savedInstanceState == null) ModelSelfTest.runIfRequested(this, intent.getStringExtra("modelSelfTest"))
        if (savedInstanceState == null) importShared(intent)
        screenRequest = launch ?: screenRequest
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
                    launchScreen = screenRequest,
                )
            }
        }
    }

    /**
     * A screen to open, from a debug launch or an import. Imports add "#<n>" (stripped by
     * RootScreen), so opening a second file still opens the screen again.
     */
    private var screenRequest by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared(intent)
    }

    /**
     * Files opened with or shared to Pocket Nomad: GGUF AI models go to Ask's AI models,
     * books and indexes to the Library. Both importers check the file again themselves.
     */
    private fun importShared(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val (models, books) = uris.partition { isGguf(it) }
        models.forEach { ModelStore.import(this, it) }
        if (books.isNotEmpty()) Importer.import(this, books)
        screenRequest = (if (books.isEmpty()) "ask:models" else "library") + "#${System.nanoTime()}"
    }

    /** GGUF files start with the four bytes "GGUF". */
    private fun isGguf(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && String(head, Charsets.US_ASCII) == "GGUF"
        } ?: false
    }.getOrDefault(false)

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
