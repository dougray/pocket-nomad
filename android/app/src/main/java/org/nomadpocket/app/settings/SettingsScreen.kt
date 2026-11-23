package org.nomadpocket.app.settings

import org.nomadpocket.i18n.tr

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nomadpocket.app.BuildConfig
import org.nomadpocket.app.R
import org.nomadpocket.app.tools.ToggleRow
import org.nomadpocket.app.ui.FormSection
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.LabeledRow
import org.nomadpocket.app.ui.accent

const val PRIVACY_URL = "https://www.pocketnomad.us/PRIVACY.html"
const val SOURCE_URL = "https://github.com/dougray/pocket-nomad"

@Composable
fun SettingsScreen(
    modifier: Modifier,
    forceDark: Boolean,
    onForceDark: (Boolean) -> Unit,
    onPrivacy: () -> Unit,
    onCoverage: () -> Unit,
) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        FormSection(footer = tr("On phones with OLED screens, black pixels use almost no power.")) {
            ToggleRow(tr("Always dark (saves battery)"), forceDark, onChange = onForceDark)
        }
        val worksPrefs = remember { context.getSharedPreferences("worksWith", android.content.Context.MODE_PRIVATE) }
        var warWalkerDismissed by remember { mutableStateOf(worksPrefs.getBoolean("warwalker.dismissed", false)) }
        val usesMap = remember { org.nomadpocket.app.map.FavoritesStore.get(context).favorites.isNotEmpty() || java.io.File(context.filesDir, "shapes.json").let { it.exists() && it.length() > 4 } }
        val usesRadio = org.nomadpocket.ham.Ham.isInitialized && org.nomadpocket.ham.Ham.service.stations.isNotEmpty()
        if (org.nomadpocket.app.WorksWith.showWarWalker(usesMap, usesRadio, warWalkerDismissed)) {
            FormSection(tr("Works with")) {
                Footnote(tr("WarWalker turns your runs and walks into a game: it maps the Wi-Fi and Bluetooth around you, and it uses Pocket Nomad's targets and coverage hexagons. Free and open source."))
                LinkRow(tr("WarWalker on GitHub"), external = true) { open(org.nomadpocket.app.WorksWith.WARWALKER_URL) }
                TextButton(onClick = { warWalkerDismissed = true; worksPrefs.edit().putBoolean("warwalker.dismissed", true).apply() }) { Text(tr("Not interested")) }
            }
        }
        run {
            val context = LocalContext.current
            val current = org.nomadpocket.i18n.Language.current
            FormSection(footer = tr("Menus and screens are translated (first-pass translations, still being reviewed). Guides and books stay in English for now: first-aid and survival text needs a qualified human translator.")) {
                Text(tr("Language"), style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    org.nomadpocket.i18n.AppLanguage.entries.forEach { l ->
                        androidx.compose.material3.FilterChip(selected = current == l, onClick = { org.nomadpocket.i18n.Language.set(context, l) },
                            label = { Text(if (l == org.nomadpocket.i18n.AppLanguage.SYSTEM) tr("System default") else l.nativeName) })
                    }
                }
            }
        }
        FormSection(tr("Battery tips")) {
            Footnote(tr("Turn on Battery Saver. Lower screen brightness. Use Airplane mode with Bluetooth on to keep mesh messaging while saving power. Keep the phone warm in cold weather; cold batteries die fast."))
        }
        FormSection(tr("Privacy"), footer = tr("No accounts, analytics or tracking. Nothing leaves this phone unless you send it: a mesh message, an SOS, or a file you export.")) {
            LinkRow(tr("How Pocket Nomad handles your data"), onClick = onPrivacy)
            LinkRow(tr("Coverage map settings…"), onClick = onCoverage)
            LinkRow(tr("Privacy policy"), external = true) { open(PRIVACY_URL) }
        }
        FormSection(tr("About")) {
            // Always on black: the logo's white lettering vanishes on a light background.
            Box(Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(16.dp)).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.logo), contentDescription = tr("Pocket Nomad"), modifier = Modifier.widthIn(max = 220.dp).size(220.dp),
                    contentScale = ContentScale.Fit)
            }
            LabeledRow(tr("Version"), BuildConfig.VERSION_NAME)
            Text(tr("Pocket Nomad is free, open-source software (Apache 2.0). It is an independent project, inspired by Project NOMAD, and not affiliated with it."),
                style = MaterialTheme.typography.bodySmall)
            LinkRow(tr("Source code"), external = true) { open(SOURCE_URL) }
            Footnote(tr("Guides in this app don't replace first-aid training. Call emergency services whenever you can."))
        }
        FormSection(tr("Licenses")) {
            License("Pocket Nomad", "Apache License 2.0. The Pocket Nomad name, logo and app icon are reserved and not covered by it.")
            License("OpenStreetMap", "Map data © OpenStreetMap contributors, under the Open Database License (ODbL). Tiles and style by OpenFreeMap.")
            License("MapLibre Native", "BSD 2-Clause License. © MapLibre contributors.")
            License("AndroidX, Jetpack Compose and Kotlin", "Apache License 2.0.")
            License("OkHttp and Timber (used by MapLibre)", "Apache License 2.0.")
            License("SQLite (bundled by AndroidX SQLite, for book search)", "Public domain.")
            License("XZ for Java", "BSD Zero Clause License (0BSD). © The XZ for Java authors.")
            License("zstd-jni and Zstandard", "BSD 2-Clause License (zstd-jni) and BSD License (Zstandard). © Luben Karavelov; © Meta Platforms, Inc.")
            License("Kiwix books", "Each book keeps its own licence, shown in the Kiwix catalogue and usually on the book's main page.")
            License("llama.cpp and ggml (Ask)", "MIT License. © 2023-2026 The ggml authors.")
            License("Qwen3 models (downloaded in Ask)", "Apache License 2.0. © Alibaba Cloud, Qwen team. Not part of the app.")
        }
    }
}

@Composable
private fun LinkRow(label: String, external: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = accent)
        if (external) Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun License(name: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Footnote(text)
    }
}

/** The privacy policy in short, readable offline. The full text is docs/PRIVACY.md. */
private val points: List<Triple<ImageVector, String, String>> = listOf(
    Triple(Icons.Filled.PhoneAndroid, "Everything stays on this phone",
        "Medical ID, pins, checklists, known radios, offline maps, mesh messages, nodes, coverage recordings and settings are stored only in the app's private storage on this phone. They are left out of Android's cloud backup. Uninstalling the app deletes them."),
    Triple(Icons.Filled.NoAccounts, "No accounts, analytics or tracking",
        "The developers never receive any of your data. There are no servers, and the app has no Google Play Services or ad libraries."),
    Triple(Icons.Filled.Psychology, "AI will run on the phone",
        "Ask isn't in this version yet. When it comes, it will run on the phone with a model file you download, and questions and answers won't be saved."),
    Triple(Icons.Filled.LocationOn, "Location only for you",
        "Used for the map, the compass, sunrise and sunset, an SOS you choose to send with your position, and the coverage scanner if you turn it on. Drive mode keeps the scanner going with the screen off, with a notification showing, until you stop it. The app never sends your location by itself."),
    Triple(Icons.Filled.SettingsInputAntenna, "Mesh messages go over the air",
        "What you send through your radio reaches other people's radios. Default public channels use a well-known key, so anyone nearby can read them. Use a private channel for anything sensitive."),
    Triple(Icons.Filled.Hexagon, "Coverage sharing is opt-in",
        "Off by default, and it needs a private zone first. Before a file exists, the phone removes your private zones, exact positions, node names and IDs, and dates finer than a month. Nothing is sent: you export the file and choose where it goes."),
    Triple(Icons.Filled.CloudDownload, "Weather, earthquakes and space weather",
        "When you tap Update in Conditions, the app asks free public services (USGS, National Weather Service, Open-Meteo, NOAA) for data and keeps it on the phone. Alerts and the forecast are asked for your position rounded to about 7 miles, never your exact location. Earthquakes, space weather and radio transmitters are whole-region downloads filtered on the phone. Updates happen only when you ask, unless you turn on updating when the app opens. Parks on the Air asks the free POTA service for a box about 100 km wide around your position rounded to about 7 miles, never your exact location. Saving elevation (hills, radio paths) and roads (directions) downloads map data for an area from free public services (AWS Open Data terrain tiles and the OpenStreetMap Overpass service); those requests reveal roughly which area you asked for, about 5 to 100 km wide, but nothing else, and no account is used. Routes, favorites, drawings and radio path checks are worked out and kept on the phone."),
    Triple(Icons.Filled.CloudDownload, "Satellite passes",
        "Orbit data for the space station and amateur radio satellites is downloaded from CelesTrak when you tap Update. Passes are worked out on the phone from your location, which is never sent."),
    Triple(Icons.Filled.CloudDownload, "Downloads you start",
        "The map loads tiles from OpenFreeMap while you're online, and saving an offline area downloads them once. Getting known radios connects to meshmap.net and map.meshcore.io. They see your IP address and the files you request, not your location. Web links open in your browser."),
)

@Composable
fun PrivacySummaryScreen(modifier: Modifier) {
    LazyColumn(modifier.padding(horizontal = 16.dp)) {
        items(points, key = { it.second }) { (icon, title, text) ->
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(top = 2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Footnote(tr("Effective November 4, 2026. The full policy is in the project's source code, docs/PRIVACY.md, and at pocketnomad.us."),
                Modifier.padding(vertical = 16.dp))
        }
    }
}
