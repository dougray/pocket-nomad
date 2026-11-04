package org.nomadpocket.app.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.accent

/** Library and Ask are not in this first Android version. These screens say so plainly. */
@Composable
private fun ComingSoon(title: String, icon: ImageVector, lead: String, points: List<String>, now: String) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(56.dp).padding(top = 8.dp))
        Text("Coming in the next version", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(lead)
        points.forEach { Text("• $it") }
        Text(now, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Footnote("Pocket Nomad is free and open source. Progress is on the project's page (Settings › Source code).")
    }
}

@Composable
fun LibraryComingSoon() = ComingSoon(
    title = "Library",
    icon = Icons.AutoMirrored.Filled.MenuBook,
    lead = "The iPhone app reads offline books in the Kiwix .zim format. Android will too:",
    points = listOf(
        "WikiMed, MedlinePlus, the Post-Disaster Library, iFixit and all of Wikipedia, downloaded once from the free Kiwix library",
        "Title and full-text search that works with no signal",
        "A PubMed survival-medicine pack",
    ),
    now = "For now, the 69 guides and checklists in Survive work with nothing downloaded.",
)

@Composable
fun AskComingSoon() = ComingSoon(
    title = "Ask",
    icon = Icons.Filled.QuestionAnswer,
    lead = "Ask a question in plain words, and an AI that runs entirely on your phone answers only from the guides and your books, citing them as [1], [2]:",
    points = listOf(
        "Runs on the phone with llama.cpp; nothing is sent anywhere",
        "Open models such as Qwen3 1.7B (1.1 GB), downloaded once from Hugging Face",
        "With no model, it shows the best passages instead",
    ),
    now = "For now, search the guides in Survive.",
)
