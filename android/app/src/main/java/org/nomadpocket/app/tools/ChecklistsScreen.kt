package org.nomadpocket.app.tools

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.nomadpocket.app.guides.Checklist
import org.nomadpocket.app.guides.GuideLibrary
import org.nomadpocket.app.ui.Brand
import org.nomadpocket.app.ui.Symbols
import org.nomadpocket.app.ui.accent
import java.io.File

/** Checked items and the user's own additions, per checklist. Saved in app-private storage. */
data class ChecklistState(
    val checked: Map<String, Set<String>> = emptyMap(),
    val custom: Map<String, List<String>> = emptyMap(),
) {
    fun isChecked(list: String, item: String) = checked[list]?.contains(item) == true

    fun toggled(list: String, item: String): ChecklistState {
        val set = checked[list].orEmpty()
        return copy(checked = checked + (list to if (item in set) set - item else set + item))
    }

    fun cleared(list: String) = copy(checked = checked + (list to emptySet()))

    fun adding(list: String, item: String) = copy(custom = custom + (list to custom[list].orEmpty() + item))

    fun toJson(): String = JSONObject().apply {
        put("checked", JSONObject(checked.mapValues { JSONArray(it.value.toList()) }))
        put("custom", JSONObject(custom.mapValues { JSONArray(it.value) }))
    }.toString()

    companion object {
        fun fromJson(json: String): ChecklistState {
            val o = JSONObject(json)
            fun JSONObject.lists(): Map<String, List<String>> =
                keys().asSequence().associateWith { k -> getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }
            return ChecklistState(
                checked = o.optJSONObject("checked")?.lists()?.mapValues { it.value.toSet() } ?: emptyMap(),
                custom = o.optJSONObject("custom")?.lists() ?: emptyMap(),
            )
        }

        private fun file(context: Context) = File(context.filesDir, "checklists.json")

        fun load(context: Context) = runCatching { fromJson(file(context).readText()) }.getOrDefault(ChecklistState())

        fun save(context: Context, state: ChecklistState) {
            val f = file(context)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(state.toJson())
            tmp.renameTo(f)
        }
    }
}

@Composable
fun ChecklistsScreen(modifier: Modifier, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val lists = remember { GuideLibrary.get(context).checklists }
    val state = remember { ChecklistState.load(context) }
    LazyColumn(modifier) {
        items(lists, key = { it.id }) { list ->
            val total = list.items.size + (state.custom[list.id]?.size ?: 0)
            val done = state.checked[list.id]?.size ?: 0
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(list.id) }.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.icon(list.symbol), contentDescription = null, tint = accent, modifier = Modifier.width(32.dp))
                Text(list.title, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                Text("$done/$total", color = if (done == total && total > 0) Brand.green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun ChecklistDetailScreen(modifier: Modifier, list: Checklist) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ChecklistState.load(context)) }
    var newItem by remember { mutableStateOf("") }
    val items = list.items + state.custom[list.id].orEmpty()
    fun update(s: ChecklistState) {
        state = s
        ChecklistState.save(context, s)
    }
    fun add() {
        val item = newItem.trim()
        if (item.isEmpty() || item in items) return
        update(state.adding(list.id, item))
        newItem = ""
    }
    LazyColumn(modifier) {
        items(items, key = { it }) { item ->
            val isChecked = state.isChecked(list.id, item)
            Row(
                Modifier.fillMaxWidth().clickable { update(state.toggled(list.id, item)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    if (isChecked) Icons.Filled.CheckCircle else Icons.Outlined.Circle, contentDescription = if (isChecked) "Checked" else "Not checked",
                    tint = if (isChecked) Brand.green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    item,
                    color = if (isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (isChecked) TextDecoration.LineThrough else null,
                )
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newItem, { newItem = it }, Modifier.weight(1f), label = { Text("Add your own item") }, singleLine = true)
                TextButton(onClick = ::add, enabled = newItem.isNotBlank()) { Text("Add") }
            }
            TextButton(onClick = { update(state.cleared(list.id)) }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Uncheck everything", color = Brand.danger)
            }
        }
    }
}
