// QsoEditor.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.ham

import org.nomadpocket.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A new contact pre-filled with sensible defaults (reports by mode, your callsign from Station settings). */
fun blankQso(call: String = "", mhz: Double = 0.0, mode: String = "FM", sig: String = "", sigInfo: String = "", myCall: String = "", myGrid: String = ""): Qso {
    val rst = if (mode in listOf("CW", "RTTY")) "599" else if (mode in listOf("FT8", "FT4", "WSPR", "JS8")) "" else "59"
    return Qso(call = call, whenMillis = System.currentTimeMillis(), mhz = mhz, mode = mode, rstSent = rst, rstRcvd = rst, myCall = myCall, myGrid = myGrid, sig = sig, sigInfo = sigInfo)
}

@Composable
fun QsoEditorDialog(initial: Qso, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { QsoLogStore.get(context) }
    val isNew = store.log.none { it.id == initial.id }
    var call by remember { mutableStateOf(initial.call) }
    var mhz by remember { mutableStateOf(if (initial.mhz > 0) initial.mhz.toString() else "") }
    var mode by remember { mutableStateOf(initial.mode) }
    var rs by remember { mutableStateOf(initial.rstSent) }
    var rr by remember { mutableStateOf(initial.rstRcvd) }
    var grid by remember { mutableStateOf(initial.grid) }
    var myGrid by remember { mutableStateOf(initial.myGrid) }
    var watts by remember { mutableStateOf(initial.watts?.toString() ?: "") }
    var name by remember { mutableStateOf(initial.name) }
    var park by remember { mutableStateOf(initial.sigInfo) }
    var note by remember { mutableStateOf(initial.comment) }
    var modeMenu by remember { mutableStateOf(false) }
    val utc = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }

    @Composable fun f(v: String, set: (String) -> Unit, label: String, caps: Boolean = false, decimal: Boolean = false) =
        OutlinedTextField(v, set, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None, keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text))

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "New contact" else call) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                f(call, { call = it }, "Their callsign", caps = true)
                Text("Time (UTC): ${utc.format(java.util.Date(initial.whenMillis))}", style = MaterialTheme.typography.bodySmall)
                f(mhz, { mhz = it }, "Frequency (MHz)${QsoBands.band(mhz.toDoubleOrNull() ?: 0.0)?.let { "  ($it)" } ?: ""}", decimal = true)
                Box {
                    OutlinedButton(onClick = { modeMenu = true }) { Text("Mode: $mode") }
                    DropdownMenu(modeMenu, { modeMenu = false }) { QsoBands.modes.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { mode = m; modeMenu = false }) } }
                }
                f(rs, { rs = it }, "Report sent"); f(rr, { rr = it }, "Report received")
                f(watts, { watts = it }, "Power (watts)", decimal = true); f(myGrid, { myGrid = it }, "Your grid", caps = true)
                f(grid, { grid = it }, "Their grid", caps = true); f(name, { name = it }, "Their name")
                f(park, { park = it }, "Park or summit (for example US-0001)", caps = true)
                f(note, { note = it }, "Notes")
                Text(if (initial.myCall.isEmpty()) "Set your callsign in Station settings." else "Your callsign: ${initial.myCall}", style = MaterialTheme.typography.bodySmall)
                if (!isNew) TextButton(onClick = { store.remove(initial.id); onDismiss() }) { Text(tr("Delete this contact"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = call.isNotBlank(), onClick = {
                val sig = if (park.isBlank()) "" else if (park.contains("-") && park.length <= 8) "POTA" else initial.sig
                val q = initial.copy(call = call.trim().uppercase(), mhz = mhz.replace(',', '.').toDoubleOrNull() ?: 0.0, mode = mode, rstSent = rs, rstRcvd = rr,
                    grid = grid.uppercase(), myGrid = myGrid.uppercase(), watts = watts.replace(',', '.').toDoubleOrNull(), name = name, sigInfo = park.uppercase(), sig = sig, comment = note)
                if (isNew) store.add(q) else store.update(q)
                onDismiss()
            }) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } })
}
