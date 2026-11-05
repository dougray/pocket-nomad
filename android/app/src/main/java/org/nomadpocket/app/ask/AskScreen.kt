package org.nomadpocket.app.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.nomadpocket.app.tools.ToolKind
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.Symbols
import org.nomadpocket.app.ui.accent
import org.nomadpocket.app.ui.cardColor
import org.nomadpocket.llm.Llama

/** The sample questions on an empty Ask screen, as on iOS. */
val ASK_SAMPLES = listOf(
    "How do I stop severe bleeding?",
    "How much bleach purifies a gallon of water?",
    "Signs of heat stroke vs heat exhaustion",
    "How do I build an emergency toilet?",
)

/**
 * Ask a question; the answer comes only from the guides and the user's books, with
 * tappable [1], [2] citations. With no model (or not enough memory), the best
 * passages are shown instead. A port of AskView in App/Sources/Ask/AskView.swift.
 */
@Composable
fun AskScreen(
    vm: AskViewModel,
    onSource: (AskPassage) -> Unit,
    onTool: (ToolKind) -> Unit,
    onModels: () -> Unit,
    launchQuestion: String? = null,
) {
    val exchanges by vm.exchanges.collectAsState()
    val preferred by vm.preferred.collectAsState()
    val models by ModelStore.models.collectAsState()
    var question by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val busy = exchanges.lastOrNull()?.isBusy == true
    val choice = remember(preferred, models, exchanges.size) { vm.currentChoice() }
    val send: (String) -> Unit = { text ->
        if (!busy && text.isNotBlank()) {
            vm.ask(text)
            question = ""
            focus.clearFocus()
        }
    }
    LaunchedEffect(launchQuestion) {
        if (!launchQuestion.isNullOrBlank() && vm.exchanges.value.isEmpty()) vm.ask(launchQuestion)
    }
    // Keep the newest question in view as its answer grows.
    LaunchedEffect(exchanges.size, exchanges.lastOrNull()?.answer?.length, exchanges.lastOrNull()?.sources?.size) {
        if (exchanges.isNotEmpty()) list.animateScrollToItem(exchanges.size - 1)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ask", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            ModelMenu(preferred, models, choice, vm::setPreferred, onModels)
            if (exchanges.isNotEmpty()) TextButton(onClick = vm::clear) { Text("Clear") }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (exchanges.isEmpty()) item { Intro(choice, send, onModels) }
            items(exchanges, key = { it.id }) { ExchangeView(it, onSource, onTool) }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about first aid, water, shelter…") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(question) }),
            )
            if (busy) {
                FilledIconButton(onClick = vm::stop, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
            } else {
                FilledIconButton(onClick = { send(question) }, enabled = question.isNotBlank(), modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Ask")
                }
            }
        }
    }
}

@Composable
private fun ModelMenu(preferred: String, models: List<ModelFile>, choice: ModelChoice, onPick: (String) -> Unit, onModels: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                choice.model?.displayName?.take(18) ?: "Passages only",
                Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Answer with", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            val options = buildList {
                add(ModelChooser.AUTO to "Automatic")
                if (Llama.isAvailable) models.forEach { add(it.name to it.displayName) }
                add(ModelChooser.NONE to "No AI (passages only)")
            }
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onPick(id); open = false },
                    trailingIcon = { if (id == preferred) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("AI models…") }, onClick = { open = false; onModels() })
        }
    }
}

@Composable
private fun Intro(choice: ModelChoice, send: (String) -> Unit, onModels: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ask a question", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Answers come only from the guides in this app and the books in your library, with links to the source. Nothing leaves your phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ASK_SAMPLES.forEach { sample ->
            OutlinedButton(onClick = { send(sample) }) { Text(sample) }
        }
        if (choice.model == null) {
            Footnote(
                choice.note ?: if (Llama.isAvailable) "No AI model is available, so you'll get the best matching passages instead. Download a model in AI models."
                else "No AI model is available, so you'll get the best matching passages instead.",
                Modifier.padding(top = 8.dp),
            )
            if (Llama.isAvailable) TextButton(onClick = onModels) { Text("AI models") }
        }
    }
}

@Composable
private fun ExchangeView(exchange: Exchange, onSource: (AskPassage) -> Unit, onTool: (ToolKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            exchange.question,
            Modifier.fillMaxWidth().background(accent.copy(alpha = 0.15f), RoundedCornerShape(14.dp)).padding(12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        when (exchange.state) {
            Exchange.State.SEARCHING -> Progress("Searching your library…")
            Exchange.State.LOADING -> Progress("Loading ${exchange.modelName ?: "the AI model"}…")
            Exchange.State.ANSWERING -> if (exchange.answer.isEmpty()) Progress("Thinking on-device…")
            Exchange.State.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(exchange.error ?: "Something went wrong.", color = MaterialTheme.colorScheme.error)
            }
            Exchange.State.DONE -> {}
        }
        exchange.note?.let { Footnote(it) }

        exchange.dose?.let { dose ->
            Row(
                Modifier.fillMaxWidth().background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.WaterDrop, contentDescription = null, tint = accent)
                Column {
                    Text("CALCULATED BY THE APP", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
                    Text(dose, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        ToolKind.related(exchange.question)?.let { tool ->
            Button(onClick = { onTool(tool) }) {
                Icon(Symbols.icon(tool.symbol), contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Exact amounts: open ${tool.title}", Modifier.padding(start = 8.dp))
            }
        }

        if (exchange.answer.isNotEmpty()) {
            val linkColor = accent
            Text(answerText(exchange.answer, linkColor) { n -> exchange.sources.getOrNull(n - 1)?.let(onSource) })
            val top = exchange.sources.firstOrNull()
            if (exchange.state == Exchange.State.DONE && top != null) {
                // Small models can drop a condition or mix up a number, so the best vetted
                // passage is always shown word for word next to the answer.
                Column(
                    Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "SOURCE [1], WORD FOR WORD: ${top.title.uppercase()}",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent,
                    )
                    Text(top.body, style = MaterialTheme.typography.bodyMedium, maxLines = 14, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (exchange.state == Exchange.State.DONE && exchange.sources.isEmpty()) {
            Text(
                "Nothing in your offline library matches that. Try different words, or add more books in Library.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (exchange.sources.isNotEmpty()) {
            Text(
                if (exchange.answer.isEmpty()) "BEST MATCHES" else "SOURCES",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            exchange.sources.forEachIndexed { i, hit -> SourceRow(i + 1, hit, showBody = exchange.answer.isEmpty()) { onSource(hit) } }
        }
        if (exchange.state == Exchange.State.DONE && exchange.answer.isNotEmpty()) {
            Footnote("AI answers can be wrong. Check the sources, and get trained help when you can.")
        }
    }
}

@Composable
private fun Progress(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceRow(number: Int, hit: AskPassage, showBody: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(cardColor, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(24.dp).background(accent.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(hit.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(hit.sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showBody) {
                Text(hit.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val inlineMarkup = Regex("""\*\*(.+?)\*\*|\[(\d+)]""")

/** The answer with **bold** shown bold and each [n] a link to source n. */
fun answerText(answer: String, linkColor: Color, onCite: (Int) -> Unit): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in inlineMarkup.findAll(answer)) {
        append(answer.substring(i, m.range.first))
        val bold = m.groups[1]?.value
        if (bold != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            val n = m.groupValues[2].toInt()
            withLink(
                LinkAnnotation.Clickable(
                    tag = "cite$n",
                    styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)),
                ) { onCite(n) },
            ) { append(m.value) }
        }
        i = m.range.last + 1
    }
    append(answer.substring(i))
}

/** A book passage shown on its own, until the Library reader can open the article. */
@Composable
fun PassageDialog(passage: AskPassage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text(passage.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(passage.sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(passage.body)
            }
        },
    )
}
