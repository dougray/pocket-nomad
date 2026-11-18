package org.nomadpocket.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nomadpocket.app.ask.AskLaunch
import org.nomadpocket.app.ask.AskPassage
import org.nomadpocket.app.ask.AskScreen
import org.nomadpocket.app.ask.AskSources
import org.nomadpocket.app.ask.AskViewModel
import org.nomadpocket.app.ask.ModelsScreen
import org.nomadpocket.app.ask.PassageDialog
import org.nomadpocket.app.ask.SourceRef as AskRef
import org.nomadpocket.app.guides.ALL_EMERGENCIES
import org.nomadpocket.app.guides.GuideLibrary
import org.nomadpocket.app.guides.GuideListScreen
import org.nomadpocket.app.guides.GuideReader
import org.nomadpocket.app.guides.SurviveScreen
import org.nomadpocket.app.library.ArticleScreen
import org.nomadpocket.app.library.BookScreen
import org.nomadpocket.app.library.GetContentScreen
import org.nomadpocket.app.library.LibraryScreen
import org.nomadpocket.app.library.PdfViewerScreen
import org.nomadpocket.app.library.SourceRef
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
    MESH("mesh", "Radio", Icons.Filled.SettingsInputAntenna),
}

@Composable
fun RootScreen(forceDark: Boolean, onForceDark: (Boolean) -> Unit, launchScreen: String?) {
    val nav = rememberNavController()
    val chromeHidden = remember { mutableStateOf(false) }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    // Go to starts from anywhere (a radio, a pin): bring the Map forward.
    val navActivations by org.nomadpocket.app.map.NavigationState.activations.collectAsState()
    LaunchedEffect(navActivations) {
        if (navActivations > 0) nav.navigate(Tab.MAP.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
    }
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
                NavGraph(nav, forceDark, onForceDark, launchScreen)
            }
        }
    }
    val context = LocalContext.current
    LaunchedEffect(launchScreen) { launchScreen?.let { openLaunchScreen(nav, it, context) } }
}

@Composable
private fun NavGraph(nav: NavHostController, forceDark: Boolean, onForceDark: (Boolean) -> Unit, launchScreen: String? = null) {
    val context = LocalContext.current
    val library = remember { GuideLibrary.get(context) }
    val back: () -> Unit = { nav.popBackStack() }
    val openPdf: (String, String) -> Unit = { id, path -> nav.navigate("pdf/$id?path=${Uri.encode(path)}") }
    val openHit: (SourceRef) -> Unit = { ref ->
        when (ref) {
            is SourceRef.Guide -> library.guide(ref.file)?.let { nav.navigate("guide/${it.id}") }
            is SourceRef.Book -> nav.navigate("article/${ref.id}?path=${Uri.encode(ref.path)}")
        }
    }
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
                    onHit = openHit,
                    onConditions = { nav.navigate("conditions") },
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
        navigation(startDestination = "ask/home", route = Tab.ASK.route) {
            composable("ask/home") { entry ->
                // One AskViewModel for the whole Ask tab, so answers survive opening a source.
                val graph = remember(entry) { nav.getBackStackEntry(Tab.ASK.route) }
                val vm: AskViewModel = viewModel(graph)
                var passage by remember { mutableStateOf<AskPassage?>(null) }
                AskScreen(
                    vm = vm,
                    onSource = { hit ->
                        when (val ref = hit.ref) {
                            is AskRef.Guide -> library.guide(ref.path)?.let { nav.navigate("ask/guide/${it.id}") }
                            is AskRef.Book -> AskSources.bookRoute?.invoke(ref.bookId, ref.path)?.let { nav.navigate(it) } ?: run { passage = hit }
                        }
                    },
                    onTool = { nav.navigate("tool/${it.id}") },
                    onModels = { nav.navigate("ask/models") },
                    launchQuestion = remember { AskLaunch.take() },
                )
                passage?.let { PassageDialog(it) { passage = null } }
            }
            composable("ask/guide/{id}") { entry ->
                val guide = library.guideById(entry.arguments?.getString("id") ?: "")
                SubScreen("", back) { m ->
                    if (guide != null) GuideReader(m, guide) else Text("Guide not found", Modifier.padding(24.dp))
                }
            }
            composable("ask/models") { entry ->
                val graph = remember(entry) { nav.getBackStackEntry(Tab.ASK.route) }
                val vm: AskViewModel = viewModel(graph)
                SubScreen("AI models", back) { m -> ModelsScreen(m, onDeleted = vm::unload) }
            }
        }
        navigation(startDestination = "library/home", route = Tab.LIBRARY.route) {
            composable("library/home") {
                LibraryScreen(onBook = { nav.navigate("book/$it") }, onHit = openHit, onGetContent = { nav.navigate("packs") })
            }
            composable("packs") { SubScreen("Get Content", back) { GetContentScreen(it) } }
        }
        // Book pages can be opened from Survive search too, so they sit outside the Library graph.
        composable("book/{id}") { entry ->
            BookScreen(entry.arguments?.getString("id") ?: "", back, onArticle = { path ->
                nav.navigate("article/${entry.arguments?.getString("id")}?path=${Uri.encode(path)}")
            }, onPdf = openPdf)
        }
        composable("article/{id}?path={path}") { entry ->
            ArticleScreen(entry.arguments?.getString("id") ?: "", entry.arguments?.getString("path") ?: "", back, openPdf)
        }
        composable("pdf/{id}?path={path}") { entry ->
            PdfViewerScreen(entry.arguments?.getString("id") ?: "", entry.arguments?.getString("path") ?: "", back)
        }
        navigation(startDestination = "map/home", route = Tab.MAP.route) {
            composable("map/home") { MapScreen(onCoverageSettings = { nav.navigate("coverage") }) }
        }
        navigation(startDestination = "mesh/home", route = Tab.MESH.route) {
            // MeshScreen has its own top bar (and its own link to Coverage settings).
            // Mesh and Ham share the Radio tab (a phone shows five tabs at most).
            composable("mesh/home") { RadioScreen(launchScreen) }
        }
        composable("coverage") { CoverageScreen(onDone = back) }
        composable("conditions") { org.nomadpocket.app.conditions.ConditionsScreen(onBack = back) }
    }
}

/**
 * Debug builds accept `-e screen <name>` to open straight to a screen, for screenshots:
 * survive, ask, ask:<question>, ask:models, library, packs, book (the first book) or book:<text in its
 * file name>, map, map:demo, mesh, mesh:demo, settings, coverage,
 * tool:<compass|sos|morse|water|medicalID|checklists|sun>, guide:<id>, category:<id>.
 */
private fun openLaunchScreen(nav: NavHostController, screen: String, context: android.content.Context) {
    val parts = screen.substringBefore('#').split(":", limit = 2)
    val kind = parts[0]
    val arg = parts.getOrElse(1) { "" }
    when (kind) {
        "ask" -> {
            if (!arg.startsWith("models") && arg.isNotBlank()) AskLaunch.question = arg
            nav.navigate(kind) { popUpTo(nav.graph.findStartDestination().id) { saveState = true } }
            if (arg.startsWith("models")) nav.navigate("ask/models")
        }
        "conditions" -> {
            org.nomadpocket.app.conditions.ConditionsStore.init(context)
            if (arg == "demo") org.nomadpocket.app.conditions.ConditionsStore.loadDemo()
            nav.navigate("conditions")
        }
        "library", "map", "mesh", "ham", "sdr" -> nav.navigate(if (kind == "ham" || kind == "sdr") "mesh" else kind) { popUpTo(nav.graph.findStartDestination().id) { saveState = true } }
        "settings" -> nav.navigate("settings")
        "coverage" -> nav.navigate("coverage")
        "tool" -> nav.navigate("tool/$arg")
        "guide" -> nav.navigate("guide/$arg")
        "category" -> nav.navigate("category/$arg")
        "packs" -> { nav.navigate(Tab.LIBRARY.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true } }; nav.navigate("packs") }
        "book" -> {
            val shelf = org.nomadpocket.library.BookShelf.get(context)
            shelf.ensureLoaded()
            val book = shelf.books.value.firstOrNull { arg.isEmpty() || it.file.name.contains(arg) } ?: return
            nav.navigate(Tab.LIBRARY.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true } }
            nav.navigate("book/${book.id}")
        }
    }
}


/** The Radio tab: Mesh (Meshtastic/MeshCore) or Ham (KISS TNC: APRS, Winlink, packet). */
@Composable
fun RadioScreen(launchScreen: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("radio", android.content.Context.MODE_PRIVATE) }
    var section by remember { mutableStateOf(if (launchScreen?.startsWith("sdr") == true) "sdr" else if (launchScreen?.startsWith("ham") == true || prefs.getBoolean("ham", false)) "ham" else prefs.getString("section", "mesh") ?: "mesh") }
    val ham = section == "ham"
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier.fillMaxWidth().background(org.nomadpocket.mesh.ui.MeshColors.background).statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            listOf("Mesh" to "mesh", "Ham" to "ham", "SDR" to "sdr").forEachIndexed { i, (label, id) ->
                SegmentedButton(
                    selected = section == id,
                    onClick = { section = id; prefs.edit().putString("section", id).putBoolean("ham", id == "ham").apply() },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                ) { Text(label) }
            }
        }
        // The switch already sits below the status bar; the screens' top bars must not add it again.
        Box(Modifier.weight(1f).consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars)) {
            if (ham) org.nomadpocket.ham.HamScreen(Modifier.fillMaxSize(), demo = launchScreen == "ham:demo", satellites = launchScreen == "ham:satellites")
            else if (section == "sdr") org.nomadpocket.app.sdr.SdrScreen(Modifier.fillMaxSize(), autoConnect = launchScreen == "sdr:connect")
            else MeshScreen(Modifier.fillMaxSize())
        }
    }
}
