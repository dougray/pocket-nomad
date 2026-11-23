package org.nomadpocket.app.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.nomadpocket.i18n.tr
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.FormSection
import org.nomadpocket.app.ui.LabeledRow
import java.util.Locale

private fun number(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

@Composable
private fun NumberField(label: String, unit: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, suffix = { Text(unit) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

/** Antenna length calculator: type a frequency, get cut lengths. */
@Composable
fun AntennaCalcScreen(modifier: Modifier) {
    var text by rememberSaveable { mutableStateOf("144.39") }
    var menu by remember { mutableStateOf(false) }
    val presets = listOf("APRS (North America)" to 144.39, "Weather radio" to 162.55, "Weather satellite" to 137.5, "Aircraft (ADS-B)" to 1090.0,
        "Mesh radio (LoRa, US)" to 915.0, "70 cm amateur" to 433.0, "20 m FT8" to 14.074, "40 m FT8" to 7.074)
    val a = number(text)?.let { RadioMath.antenna(it) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        FormSection(tr("Frequency")) {
            NumberField(tr("Frequency"), "MHz", text) { text = it }
            OutlinedButton(onClick = { menu = true }) { Text(tr("Pick a common one")) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                presets.forEach { (name, mhz) -> DropdownMenuItem(text = { Text(tr(name)) }, onClick = { text = mhz.toString(); menu = false }) }
            }
        }
        if (a != null) {
            FormSection(tr("Whip or ground plane (one element)")) {
                LabeledRow(tr("Quarter wave"), RadioMath.lengthText(a.quarterWaveMetres))
                LabeledRow(tr("Start cutting at"), RadioMath.lengthText(a.cutLong(a.quarterWaveMetres)))
            }
            FormSection(tr("Dipole (two equal legs)")) {
                LabeledRow(tr("Each leg"), RadioMath.lengthText(a.halfWaveLegMetres))
                LabeledRow(tr("Half wave, tip to tip"), RadioMath.lengthText(a.halfWaveTotalMetres))
            }
            FormSection(tr("V-dipole (weather satellites)")) {
                LabeledRow(tr("Each element"), RadioMath.lengthText(a.vDipoleElementMetres))
                LabeledRow(tr("Angle between them"), "${RadioMath.V_DIPOLE_ANGLE_DEGREES}°")
            }
            FormSection(footer = tr("Cut about 3 % long, then trim a little at a time for the best signal. Lengths assume a bare wire or rod; a thick element or one near other metal needs to be a little shorter.")) {
                LabeledRow(tr("Wavelength in air"), RadioMath.lengthText(a.wavelengthMetres))
            }
        } else {
            Footnote(tr("Enter a frequency between 0.1 and 6000 MHz."), Modifier.padding(16.dp))
        }
    }
}

/** Solar node power calculator: will the panel and battery keep a node alive? */
@Composable
fun SolarNodeScreen(modifier: Modifier) {
    var panel by rememberSaveable { mutableStateOf("2") }
    var sun by rememberSaveable { mutableStateOf(3.5) }
    var volts by rememberSaveable { mutableStateOf("3.7") }
    var mah by rememberSaveable { mutableStateOf("5000") }
    var draw by rememberSaveable { mutableStateOf("75") }
    val r = RadioMath.solar(number(panel) ?: -1.0, sun, number(volts) ?: 0.0, number(mah) ?: 0.0, number(draw) ?: 0.0)
    Column(modifier.verticalScroll(rememberScrollState())) {
        FormSection(tr("Panel")) {
            NumberField(tr("Rated power"), "W", panel) { panel = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2.0 to "Winter or cloudy", 3.5 to "Average", 5.0 to "Summer, clear").forEach { (h, name) ->
                    FilterChip(selected = sun == h, onClick = { sun = h }, label = { Text(tr(name)) })
                }
            }
        }
        FormSection(tr("Battery (all cells together)")) {
            NumberField(tr("Voltage"), "V", volts) { volts = it }
            NumberField(tr("Capacity"), "mAh", mah) { mah = it }
        }
        FormSection(tr("Node"), footer = tr("About 75 mA while listening for the Heltec V4 (its datasheet). Measure yours with a USB power meter for the best answer.")) {
            NumberField(tr("Average draw"), "mA", draw) { draw = it }
        }
        if (r != null) {
            FormSection(tr("Result"), footer = tr("Real runtime is shorter: batteries should not be run flat, cold cuts capacity, and panels rarely reach their rating. Use more panel and battery than the sum asks for.")) {
                LabeledRow(tr("Node uses"), String.format(Locale.US, "%.1f Wh %s", r.loadWhPerDay, tr("a day")))
                LabeledRow(tr("Panel makes"), String.format(Locale.US, "%.1f Wh %s", r.panelWhPerDay, tr("a day")))
                LabeledRow(tr("Battery holds"), String.format(Locale.US, "%.1f Wh", r.batteryWh))
                LabeledRow(tr("With no sun, a full battery lasts"), String.format(Locale.US, "%.1f %s", r.autonomyDays, tr("days")))
                when (val v = r.verdict) {
                    RadioMath.Verdict.KeepsUp -> Text(tr("The panel keeps up, with room to spare."), color = Color(0xFF34C759))
                    RadioMath.Verdict.Tight -> Text(tr("Tight: it only just keeps up. A few cloudy days will drain the battery."), color = Color(0xFFFF9500))
                    is RadioMath.Verdict.RunsDown -> Text(String.format(Locale.US, tr("The panel is too small: even in this sun a full battery runs down in about %.1f days."), v.daysUntilEmpty), color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Footnote(tr("Fill in every box with a number above zero."), Modifier.padding(16.dp))
        }
    }
}
