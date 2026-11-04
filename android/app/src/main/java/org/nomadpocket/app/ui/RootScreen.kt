package org.nomadpocket.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import org.nomadpocket.app.guides.ALL_EMERGENCIES
import org.nomadpocket.app.guides.GuideLibrary
import org.nomadpocket.app.guides.GuideListScreen
import org.nomadpocket.app.guides.GuideReader
import org.nomadpocket.app.guides.SurviveScreen
import org.nomadpocket.app.library.AskComingSoon
import org.nomadpocket.app.library.LibraryComingSoon
import org.nomadpocket.app.map.MapScreen
import org.nomadpocket.app.settings.PrivacySummaryScreen
import org.nomadpocket.app.settings.SettingsScreen
import org.nomadpocket.app.tools.ChecklistDetailScreen
import org.nomadpocket.app.tools.ChecklistsScreen
import org.nomadpocket.app.tools.CompassScreen
import org.nomadpocket.app.tools.DaylightScreen
import org.nomadpocket.app.tools.MedicalIDScreen
import org.nomadpocket.app.tools.MorseScreen
import org.nomadpocket.app.tools.SignalLightScreen
import org.nomadpocket.app.tools.ToolKind
import org.nomadpocket.app.tools.WaterScreen
import org.nomadpocket.mesh.CoverageScreen
import org.nomadpocket.mesh.MeshScreen

/** True while a screen (the full-screen signal flash) wants the bottom bar hidden. */
val LocalChromeHidden = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }
/** The "Always dark" setting, for the guide reader's stylesheet. */
val LocalForceDark = compositionLocalOf { true }

enum class Tab(val route: String, val title: String, val icon: ImageVector) {
    SURVIVE("survive", "Survive", Icons.Filled.HealthAndSafety),
    ASK("ask", "Ask", Icons.Filled.QuestionAnswer),
    LIBRARY("library", "Library", Icons.AutoMirrored.Filled.MenuBook),
    MAP("map", "Map", Icons.Filled.Map),
    MESH("mesh", "Mesh", Icons.Filled.SettingsInputAntenna),
}

@Composable
fun RootScreen(forceDark: Boolean, onForceDark: (Boolean) -> Unit, launchScreen: String?) {
    val nav = rememberNavController()
    val chromeHidden = remember { mutableStateOf(false) }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    CompositionLocalProvider(LocalChromeHidden provides chromeHidden, LocalForceDark provides forceDark) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!chromeHidden.value) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab.entries.forEach { tab ->
                            val selected = current?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.title) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                NavGraph(nav, forceDark, onForceDark)
            }
        }
    }
    LaunchedEffect(launchScreen) { launchScreen?.let { openLaunchScreen(nav, it) } }
}

@Composable
private fun NavGraph(nav: NavHostController, forceDark: Boolean, onForceDark: (Boolean) -> Unit) {
    val context = LocalContext.current
    val library = remember { GuideLibrary.get(context) }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = Tab.SURVIVE.route) {
        navigation(startDestination = "survive/home", route = Tab.SURVIVE.route) {
            composable("survive/home") {
                var query by rememberSaveable { mutableStateOf("") }
                SurviveScreen(
                    query = query, onQuery = { query = it },
                    onGuide = { nav.navigate("guide/${it.id}") },
                    onCategory = { nav.navigate("category/$it") },
                    onTool = { nav.navigate("tool/${it.id}") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("category/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: ""
                val (title, guides) = if (id == ALL_EMERGENCIES) "All Emergencies" to library.urgent
                else library.category(id)?.let { it.title to it.guides } ?: ("Guides" to emptyList())
                SubScreen(title, back) { m -> GuideListScreen(m, guides) { nav.navigate("guide/${it.id}") } }
            }
            composable("guide/{id}") { entry ->
                val guide = library.guideById(entry.arguments?.getString("id") ?: "")
                // The page has its own large heading, so the bar stays empty.
                SubScreen("", back) { m ->
                    if (guide != null) GuideReader(m, guide) else Text("Guide not found", Modifier.padding(24.dp))
                }
            }
            composable("tool/{id}") { entry ->
                when (ToolKind.from(entry.arguments?.getString("id") ?: "")) {
                    ToolKind.COMPASS -> SubScreen("Compass", back) { CompassScreen(it) }
                    ToolKind.SOS -> SignalLightScreen("SOS", "SOS Light", back)
                    ToolKind.MORSE -> SubScreen("Morse Code", back) { MorseScreen(it) { nav.navigate("morse-sender") } }
                    ToolKind.WATER -> SubScreen("Water Purifier", back) { m ->
                        WaterScreen(m) { library.category("water-sanitation")?.guides?.firstOrNull()?.let { nav.navigate("guide/${it.id}") } }
                    }
                    ToolKind.MEDICAL_ID -> MedicalIDScreen(back)
                    ToolKind.CHECKLISTS -> SubScreen("Checklists", back) { m -> ChecklistsScreen(m) { nav.navigate("checklist/$it") } }
                    ToolKind.SUN -> SubScreen("Daylight", back) { DaylightScreen(it) }
                    null -> SubScreen("Tool", back) { }
                }
            }
            composable("morse-sender") { SignalLightScreen("HELP", "Morse Sender", back) }
            composable("checklist/{id}") { entry ->
                val list = library.checklists.firstOrNull { it.id == entry.arguments?.getString("id") }
                SubScreen(list?.title ?: "Checklist", back) { m -> if (list != null) ChecklistDetailScreen(m, list) }
            }
            composable("settings") {
                SubScreen("Settings", back) { m ->
                    SettingsScreen(m, forceDark, onForceDark, onPrivacy = { nav.navigate("privacy") }, onCoverage = { nav.navigate("coverage") })
                }
            }
            composable("privacy") { SubScreen("Your data", back) { PrivacySummaryScreen(it) } }
        }
        composable(Tab.ASK.route) { AskComingSoon() }
        composable(Tab.LIBRARY.route) { LibraryComingSoon() }
        navigation(startDestination = "map/home", route = Tab.MAP.route) {
            composable("map/home") { MapScreen(onCoverageSettings = { nav.navigate("coverage") }) }
        }
        navigation(startDestination = "mesh/home", route = Tab.MESH.route) {
            // MeshScreen has its own top bar (and its own link to Coverage settings).
            composable("mesh/home") { MeshScreen(Modifier.fillMaxSize()) }
        }
        composable("coverage") { CoverageScreen(onDone = back) }
    }
}

/**
 * Debug builds accept `-e screen <name>` to open straight to a screen, for screenshots:
 * survive, ask, library, map, map:demo, mesh, mesh:demo, settings, coverage,
 * tool:<compass|sos|morse|water|medicalID|checklists|sun>, guide:<id>, category:<id>.
 */
private fun openLaunchScreen(nav: NavHostController, screen: String) {
    val parts = screen.split(":", limit = 2)
    val kind = parts[0]
    val arg = parts.getOrElse(1) { "" }
    when (kind) {
        "ask", "library", "map", "mesh" -> nav.navigate(kind) { popUpTo(nav.graph.findStartDestination().id) { saveState = true } }
        "settings" -> nav.navigate("settings")
        "coverage" -> nav.navigate("coverage")
        "tool" -> nav.navigate("tool/$arg")
        "guide" -> nav.navigate("guide/$arg")
        "category" -> nav.navigate("category/$arg")
    }
}
