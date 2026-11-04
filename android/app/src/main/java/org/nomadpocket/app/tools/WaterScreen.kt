package org.nomadpocket.app.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nomadpocket.app.location.rememberLocation
import org.nomadpocket.app.ui.FormSection
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.accent
import kotlin.math.max
import kotlin.math.min

@Composable
fun WaterScreen(modifier: Modifier, onOpenGuide: () -> Unit) {
    var amount by rememberSaveable { mutableDoubleStateOf(1.0) }
    var unit by rememberSaveable { mutableStateOf(WaterCalculator.Unit.GALLONS) }
    var strength by rememberSaveable { mutableStateOf(WaterCalculator.Strength.SIX) }
    var cloudy by rememberSaveable { mutableStateOf(false) }
    val location = rememberLocation()
    val drops = WaterCalculator.drops(WaterCalculator.gallons(amount, unit), strength, cloudy)
    val altitude = location?.takeIf { it.hasAltitude() }?.altitude

    Column(modifier.verticalScroll(rememberScrollState())) {
        FormSection("Water") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val unitName = if (amount == 1.0) unit.label.dropLast(1) else unit.label
                Text("${WaterCalculator.formatAmount(amount)} $unitName", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                // Same steps as iOS: quarters below 2, then whole units; 0.25 to 100.
                FilledTonalIconButton(onClick = { amount = max(0.25, amount - (if (amount < 2) 0.25 else 1.0)) }) {
                    Icon(Icons.Filled.Remove, contentDescription = "Less")
                }
                FilledTonalIconButton(onClick = { amount = min(100.0, amount + (if (amount < 2) 0.25 else 1.0)) }) {
                    Icon(Icons.Filled.Add, contentDescription = "More")
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WaterCalculator.Unit.entries.forEachIndexed { i, u ->
                    SegmentedButton(unit == u, { unit = u }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(u.label.replaceFirstChar { it.uppercase() }) }
                }
            }
            ToggleRow("Cloudy, colored or very cold", cloudy) { cloudy = it }
        }
        FormSection("Plain, unscented bleach") {
            Text("Strength on label", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WaterCalculator.Strength.entries.forEachIndexed { i, s ->
                    SegmentedButton(strength == s, { strength = s }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(s.label) }
                }
            }
        }
        FormSection("Add") {
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$drops drops", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(WaterCalculator.teaspoons(drops), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconLine(Icons.Filled.Timer, "Stir and wait 30 minutes.")
            IconLine(Icons.Filled.Air, "It should smell slightly of chlorine. If not, add the same dose again and wait 15 more minutes.")
            IconLine(Icons.Filled.FilterAlt, "If it is cloudy, let it settle and pour it through a clean cloth or coffee filter first.")
        }
        FormSection("Or boil it") {
            IconLine(Icons.Filled.LocalFireDepartment, WaterCalculator.boilText(altitude))
            Footnote("Boiling is the surest way to kill germs. Let it cool, and pour it between clean containers to improve the taste.")
        }
        FormSection {
            Footnote("Never use scented, color-safe or \"splash-less\" bleach, or bleach with added cleaners. Bleach and boiling don't remove chemicals, fuel or salt; find another source if you suspect them.")
            Text("Read the full water guide", color = accent, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenGuide).padding(vertical = 6.dp))
        }
    }
}

@Composable
fun IconLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        Text(text)
    }
}
