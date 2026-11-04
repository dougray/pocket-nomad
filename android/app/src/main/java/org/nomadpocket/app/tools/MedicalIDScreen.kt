package org.nomadpocket.app.tools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.nomadpocket.app.ui.Brand
import org.nomadpocket.app.ui.FormSection
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.SubScreen
import org.nomadpocket.app.ui.accent
import java.io.File
import java.util.UUID

data class MedicalInfo(
    val name: String = "",
    val birthDate: String = "",
    val bloodType: String = "",
    val allergies: String = "",
    val medications: String = "",
    val conditions: String = "",
    val notes: String = "",
    val contacts: List<Contact> = emptyList(),
) {
    data class Contact(val id: String = UUID.randomUUID().toString(), val name: String = "", val relation: String = "", val phone: String = "")

    val isEmpty: Boolean get() = copy(contacts = emptyList()) == MedicalInfo() && contacts.isEmpty()

    fun toJson(): String = JSONObject().apply {
        put("name", name); put("birthDate", birthDate); put("bloodType", bloodType); put("allergies", allergies)
        put("medications", medications); put("conditions", conditions); put("notes", notes)
        put("contacts", JSONArray(contacts.map { JSONObject().put("id", it.id).put("name", it.name).put("relation", it.relation).put("phone", it.phone) }))
    }.toString()

    companion object {
        fun fromJson(json: String): MedicalInfo {
            val o = JSONObject(json)
            val c = o.optJSONArray("contacts") ?: JSONArray()
            return MedicalInfo(
                o.optString("name"), o.optString("birthDate"), o.optString("bloodType"), o.optString("allergies"),
                o.optString("medications"), o.optString("conditions"), o.optString("notes"),
                (0 until c.length()).map {
                    val x = c.getJSONObject(it)
                    Contact(x.optString("id", UUID.randomUUID().toString()), x.optString("name"), x.optString("relation"), x.optString("phone"))
                },
            )
        }

        /** App-private storage, excluded from cloud backup: it stays on this phone. */
        private fun file(context: Context) = File(context.noBackupFilesDir, "medical-id.json")

        fun load(context: Context): MedicalInfo =
            runCatching { fromJson(file(context).readText()) }.getOrDefault(MedicalInfo())

        fun save(context: Context, info: MedicalInfo) {
            val f = file(context)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(info.toJson())
            tmp.renameTo(f)
        }
    }
}

/** What a helper or medic needs to know about you (and your family) when you can't say it. */
@Composable
fun MedicalIDScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf(MedicalInfo.load(context)) }
    var editing by remember { mutableStateOf(false) }
    fun update(new: MedicalInfo) {
        info = new
        MedicalInfo.save(context, new)
    }
    val showEditor = editing || info.isEmpty

    SubScreen("Medical ID", onBack, actions = {
        TextButton(onClick = {
            if (editing) MedicalInfo.save(context, info)
            editing = !editing
        }) { Text(if (editing) "Done" else "Edit") }
    }) { modifier ->
        if (showEditor) Editor(modifier, info, ::update) else Card(modifier, info)
    }
}

@Composable
private fun Card(modifier: Modifier, info: MedicalInfo) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.MedicalServices, contentDescription = null, tint = Brand.danger)
            Text("MEDICAL ID", color = Brand.danger, fontWeight = FontWeight.SemiBold)
        }
        Text(info.name.ifEmpty { "Name not set" }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Field("Born", info.birthDate)
        Field("Blood type", info.bloodType)
        Field("Allergies", info.allergies)
        Field("Medications", info.medications)
        Field("Conditions", info.conditions)
        Field("Notes", info.notes)
        if (info.contacts.isNotEmpty()) {
            Text("EMERGENCY CONTACTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.contacts.forEach { c ->
                Column {
                    Text("${c.name}${if (c.relation.isEmpty()) "" else " (${c.relation})"}", fontWeight = FontWeight.SemiBold)
                    Text(c.phone, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Footnote("Also add your medical information and emergency contacts in your phone's Safety or Emergency settings, so they show on the lock screen.", Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Editor(modifier: Modifier, info: MedicalInfo, update: (MedicalInfo) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        FormSection("You") {
            Input("Full name", info.name) { update(info.copy(name = it)) }
            Input("Date of birth", info.birthDate) { update(info.copy(birthDate = it)) }
            Input("Blood type (e.g. O+)", info.bloodType) { update(info.copy(bloodType = it)) }
        }
        FormSection("Health") {
            Input("Allergies", info.allergies, multiline = true) { update(info.copy(allergies = it)) }
            Input("Medications and doses", info.medications, multiline = true) { update(info.copy(medications = it)) }
            Input("Medical conditions", info.conditions, multiline = true) { update(info.copy(conditions = it)) }
            Input("Other notes (organ donor, implants, DNR…)", info.notes, multiline = true) { update(info.copy(notes = it)) }
        }
        FormSection("Emergency contacts") {
            info.contacts.forEachIndexed { i, c ->
                fun set(new: MedicalInfo.Contact) = update(info.copy(contacts = info.contacts.toMutableList().also { it[i] = new }))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Input("Name", c.name) { set(c.copy(name = it)) }
                        Input("Relationship", c.relation) { set(c.copy(relation = it)) }
                        Input("Phone", c.phone, keyboard = KeyboardType.Phone) { set(c.copy(phone = it)) }
                    }
                    IconButton(onClick = { update(info.copy(contacts = info.contacts.filterIndexed { j, _ -> j != i })) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove contact")
                    }
                }
            }
            TextButton(onClick = { update(info.copy(contacts = info.contacts + MedicalInfo.Contact())) }) {
                Text("Add contact", color = accent)
            }
        }
        FormSection { Footnote("Stored only on this phone.") }
    }
}

@Composable
private fun Input(label: String, value: String, multiline: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = !multiline,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
    )
}
