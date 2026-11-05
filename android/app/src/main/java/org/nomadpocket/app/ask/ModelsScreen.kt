package org.nomadpocket.app.ask

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.nomadpocket.app.ui.FormSection
import org.nomadpocket.app.ui.LabeledRow
import org.nomadpocket.llm.Llama
import java.util.Locale

private fun gb(bytes: Long) = String.format(Locale.US, "%.1f GB", bytes / 1e9)

/**
 * Download, add and delete the AI models Ask uses (Library › Get Content › AI models
 * on iOS). Everything is checked against its SHA-256 before Ask will use it.
 */
@Composable
fun ModelsScreen(modifier: Modifier, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val models by ModelStore.models.collectAsState()
    val transfers by ModelStore.transfers.collectAsState()
    val free = remember { mutableLongStateOf(ModelStore.freeMemory(context)) }
    LaunchedEffect(Unit) {
        while (true) { delay(3_000); free.longValue = ModelStore.freeMemory(context) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ModelStore.import(context, uri)
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        FormSection(
            footer = "Models run entirely on this phone with llama.cpp. They are downloaded once from Hugging Face; " +
                "use Wi-Fi, they are large. Ask works without one: it shows the best passages instead.",
        ) {
            LabeledRow("Free memory now", gb(free.longValue))
            if (!Llama.isAvailable) {
                Text("The AI engine isn't included in this build or doesn't run on this phone, so Ask shows passages only.", color = MaterialTheme.colorScheme.error)
            }
        }
        ModelCatalog.items.forEach { item ->
            val installed = models.firstOrNull { it.name == item.id }
            val t = transfers[item.id]
            FormSection(header = item.title) {
                Text(item.summary, style = MaterialTheme.typography.bodyMedium)
                if (installed == null && t == null && free.longValue < item.minFreeBytes) {
                    Text(
                        "This phone has ${gb(free.longValue)} free right now; this model needs about ${gb(item.minFreeBytes)}.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    installed != null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("On this phone", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { onDeleted(); ModelStore.delete(item.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    t != null -> TransferRow(t, onResume = { ModelStore.download(item) }, onRetry = { ModelStore.download(item) })
                    else -> Button(onClick = { ModelStore.download(item) }) { Text("Download (${gb(item.size)})") }
                }
            }
        }
        val others = models.filter { ModelCatalog.byId(it.name) == null }
        val otherTransfers = transfers.values.filter { ModelCatalog.byId(it.name) == null }
        FormSection(
            header = "Other models",
            footer = "You can also open a .gguf file with Pocket Nomad from Files or a browser download. " +
                "Models not listed above aren't tested with Ask; small instruction-tuned ones work best.",
        ) {
            others.forEach { m ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName, fontWeight = FontWeight.SemiBold)
                        Text(gb(m.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onDeleted(); ModelStore.delete(m.name) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
            otherTransfers.forEach { TransferRow(it, onResume = {}, onRetry = null) }
            OutlinedButton(onClick = { pick.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Add a .gguf file…") }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, onResume: () -> Unit, onRetry: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (t.expected > 0) LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        val amount = if (t.expected > 0) "${gb(t.received)} of ${gb(t.expected)}" else gb(t.received)
        when (t.state) {
            Transfer.State.RUNNING -> Row(Modifier.fillMaxWidth()) {
                Text("Downloading $amount", Modifier.weight(1f))
                TextButton(onClick = { ModelStore.pause(t.name) }) { Text("Pause") }
            }
            Transfer.State.CHECKING -> Text("Checking the file…")
            Transfer.State.PAUSED -> Row(Modifier.fillMaxWidth()) {
                Text("Paused at $amount", Modifier.weight(1f))
                TextButton(onClick = onResume) { Text("Resume") }
                TextButton(onClick = { ModelStore.delete(t.name) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            Transfer.State.FAILED -> Column {
                Text(t.error ?: "The download stopped.", color = MaterialTheme.colorScheme.error)
                Row {
                    if (onRetry != null) TextButton(onClick = onRetry) { Text("Try again") }
                    TextButton(onClick = { ModelStore.dismiss(t.name) }) { Text("Dismiss") }
                }
            }
        }
    }
}
