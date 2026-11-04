package org.nomadpocket.app.guides

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.nomadpocket.app.tools.ToolKind
import org.nomadpocket.app.ui.Brand
import org.nomadpocket.app.ui.LocalForceDark
import org.nomadpocket.app.ui.Symbols
import org.nomadpocket.app.ui.accent
import org.nomadpocket.app.ui.cardColor

@Composable
fun SurviveScreen(
    query: String,
    onQuery: (String) -> Unit,
    onGuide: (Guide) -> Unit,
    onCategory: (String) -> Unit,
    onTool: (ToolKind) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val library = remember { GuideLibrary.get(context) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Survive", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search guides") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = cardColor, focusedContainerColor = cardColor, unfocusedBorderColor = cardColor),
        )
        if (query.isNotBlank()) {
            val hits = remember(query) { library.search(query) }
            LazyColumn(Modifier.fillMaxSize()) {
                if (hits.isEmpty()) {
                    item { Text("No guides match “$query”.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(hits, key = { it.id }) { g ->
                    GuideRow(g, Modifier.clickable { onGuide(g) })
                    HorizontalDivider()
                }
            }
        } else {
            SurviveHome(library, onGuide, onCategory, onTool)
        }
    }
}

/** The Survive home: emergency guides, tools and guide categories. */
@Composable
private fun SurviveHome(library: GuideLibrary, onGuide: (Guide) -> Unit, onCategory: (String) -> Unit, onTool: (ToolKind) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (library.urgent.isNotEmpty()) {
            Section("Emergency") {
                library.emergencies.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { g ->
                            Row(
                                Modifier.weight(1f).heightIn(min = 56.dp)
                                    .background(Brand.danger.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                                    .clickable { onGuide(g) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = Brand.danger, modifier = Modifier.size(20.dp))
                                Text(g.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Row(Modifier.clickable { onCategory(ALL_EMERGENCIES) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    Text(" All emergencies (${library.urgent.size})", color = accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Section("Tools") {
            ToolKind.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tool -> ToolTile(tool, Modifier.weight(1f)) { onTool(tool) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Section("Guides") {
            library.categories.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(14.dp)).clickable { onCategory(c.id) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Symbols.icon(c.symbol), contentDescription = null, tint = accent, modifier = Modifier.width(36.dp).size(28.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(c.title, fontWeight = FontWeight.SemiBold)
                        Text(c.summary ?: "${c.guides.size} guides", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Text(
            "These guides don't replace training. If you can reach emergency services, call 911 (or your local number) first.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

const val ALL_EMERGENCIES = "emergencies"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        content()
    }
}

/** A large, glove-friendly tile. */
@Composable
private fun ToolTile(tool: ToolKind, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 104.dp).background(cardColor, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(Symbols.icon(tool.symbol), contentDescription = null, tint = Brand.amber, modifier = Modifier.size(30.dp))
        Text(tool.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun GuideRow(guide: Guide, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (guide.urgent) Icon(Icons.Filled.Warning, contentDescription = "Urgent", tint = Brand.danger, modifier = Modifier.size(18.dp))
            Text(guide.title, fontWeight = FontWeight.SemiBold)
        }
        Text(guide.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GuideListScreen(modifier: Modifier, guides: List<Guide>, onGuide: (Guide) -> Unit) {
    LazyColumn(modifier) {
        items(guides, key = { it.id }) { g ->
            GuideRow(g, Modifier.clickable { onGuide(g) })
            HorizontalDivider()
        }
    }
}

/** A guide in a WebView with the iOS reader's look. Nothing is loaded from the network. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GuideReader(modifier: Modifier, guide: Guide) {
    val context = LocalContext.current
    val dark = LocalForceDark.current || isSystemInDarkTheme()
    val html = remember(guide.id, dark) { GuidePage.page(guide.title, GuideLibrary.html(context, guide), dark) }
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.background(bg)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webViewClient = object : WebViewClient() {
                        // Links in guides open in the browser; the page itself never navigates.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme ?: ""
                            if (scheme in setOf("http", "https", "mailto", "tel")) {
                                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                            }
                            return true
                        }
                    }
                }
            },
            update = {
                if (it.tag != html) {
                    it.tag = html
                    it.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
                }
            },
        )
    }
}

