package org.nomadpocket.app.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.nomadpocket.app.location.rememberLocation
import org.nomadpocket.app.ui.FormSection
import org.nomadpocket.app.ui.Footnote
import org.nomadpocket.app.ui.LabeledRow
import java.time.Instant
import java.time.ZoneId
import java.util.Date

@Composable
fun DaylightScreen(modifier: Modifier) {
    val context = LocalContext.current
    val location = rememberLocation()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Instant.now()
        }
    }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    fun fmt(i: Instant?) = i?.let { timeFormat.format(Date.from(it)) } ?: "—"

    Column(modifier.verticalScroll(rememberScrollState())) {
        if (location != null) {
            val day = SolarCalculator.day(now, location.latitude, location.longitude, ZoneId.systemDefault())
            FormSection {
                val sunrise = day.sunrise
                val sunset = day.sunset
                val dawn = day.civilDawn
                when {
                    sunset != null && sunset > now && sunrise != null && sunrise < now ->
                        Big("Daylight left", SolarCalculator.duration(sunset.epochSecond - now.epochSecond))
                    dawn != null && dawn > now -> Big("First light in", SolarCalculator.duration(dawn.epochSecond - now.epochSecond))
                    else -> Big("After dark", "Plan for night")
                }
            }
            FormSection("Today") {
                LabeledRow("First light", fmt(day.civilDawn))
                LabeledRow("Sunrise", fmt(day.sunrise))
                LabeledRow("Sunset", fmt(day.sunset))
                LabeledRow("Last light", fmt(day.civilDusk))
            }
            FormSection("Moon") {
                IconLine(Icons.Filled.Brightness2, SolarCalculator.moonName(SolarCalculator.moonPhase(now)))
                Footnote("A bright moon (near full) gives enough light to move at night.")
            }
        } else {
            FormSection {
                Text("Waiting for a GPS fix to work out sunrise and sunset where you are.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FormSection {
            Footnote("Quick check: hold your hand at arm's length with fingers flat under the sun. Each finger between the sun and the horizon is about 15 minutes of daylight.")
        }
    }
}

@Composable
private fun Big(label: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}
