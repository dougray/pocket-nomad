package org.nomadpocket.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

/**
 * The watch app: Home, SOS, Compass and First aid, with swipe-to-go-back.
 *
 * Opens straight to a screen with `-e screen sos|compass|firstaid|guide:<id>`
 * (the SOS tile uses `sos`; `adb shell am start` uses the rest for screenshots).
 */
class MainActivity : ComponentActivity() {
    private var launchScreen by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launchScreen = intent.getStringExtra(EXTRA_SCREEN)
        setContent {
            WearTheme {
                WearApp(launchScreen) { launchScreen = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchScreen = intent.getStringExtra(EXTRA_SCREEN)
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        /** The manifest alias the SOS tile opens (the tile renderer is another app, so it is exported). */
        const val SOS_SHORTCUT = "org.nomadpocket.wear.SosShortcut"
    }
}

/** Routes for a `screen` extra: home first, so swiping back always lands on Home. */
internal fun routesFor(screen: String?): List<String> = when {
    screen == null -> emptyList()
    screen == "sos" -> listOf(Route.SOS)
    screen == "compass" || screen == "position" -> listOf(Route.COMPASS)
    screen == "firstaid" -> listOf(Route.FIRST_AID)
    screen.startsWith("guide:") -> listOf(Route.FIRST_AID, Route.guide(screen.removePrefix("guide:")))
    else -> emptyList()
}

internal object Route {
    const val HOME = "home"
    const val SOS = "sos"
    const val COMPASS = "compass"
    const val FIRST_AID = "firstaid"
    const val GUIDE = "guide/{id}"
    fun guide(id: String) = "guide/$id"
}

@Composable
private fun WearApp(launchScreen: String?, consumed: () -> Unit) {
    val nav = rememberSwipeDismissableNavController()
    val context = LocalContext.current
    val cards = remember { FirstAidLibrary.cards(context) }
    LaunchedEffect(launchScreen) {
        val routes = routesFor(launchScreen)
        if (routes.isNotEmpty()) {
            nav.popBackStack(Route.HOME, inclusive = false)
            routes.forEach { nav.navigate(it) }
        }
        if (launchScreen != null) consumed()
    }
    AppScaffold {
        SwipeDismissableNavHost(navController = nav, startDestination = Route.HOME) {
            composable(Route.HOME) {
                HomeScreen(
                    onSos = { nav.navigate(Route.SOS) },
                    onCompass = { nav.navigate(Route.COMPASS) },
                    onFirstAid = { nav.navigate(Route.FIRST_AID) },
                )
            }
            composable(Route.SOS) { SosScreen(onStop = { nav.popBackStack() }) }
            composable(Route.COMPASS) { CompassScreen() }
            composable(Route.FIRST_AID) { FirstAidListScreen(cards) { nav.navigate(Route.guide(it)) } }
            composable(Route.GUIDE) { entry ->
                val id = entry.arguments?.getString("id")
                FirstAidDetailScreen(cards.firstOrNull { it.id == id })
            }
        }
    }
}
