package org.nomadpocket.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Brand colours sampled from the Pocket Nomad logo (App/Sources/App/Theme.swift). */
object Brand {
    /** Top of the "NOMAD" lettering. */
    val amber = Color(0xFFFC9712)
    /** Bottom of the lettering. */
    val orange = Color(0xFFFC840C)
    /** The compass star's highlight. */
    val highlight = Color(0xFFFEBB1E)
    /** Amber darkened for text on white, where bright amber is too faint. */
    val amberOnLight = Color(0xFFC25B00)
    val danger = Color(0xFFF24236)
    val green = Color(0xFF34C759)
    val gradient = Brush.verticalGradient(listOf(amber, orange))
}

private val DarkColors = darkColorScheme(
    primary = Brand.amber,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2400),
    onPrimaryContainer = Brand.highlight,
    secondary = Brand.orange,
    onSecondary = Color.Black,
    tertiary = Brand.highlight,
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
    surface = Color.Black,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFFA9ADB3),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerLow = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF1C1C1E),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    error = Color(0xFFFF6B5E),
    outline = Color(0xFF55595F),
)

private val LightColors = lightColorScheme(
    primary = Brand.amberOnLight,
    onPrimary = Color.White,
    secondary = Brand.orange,
    tertiary = Brand.amberOnLight,
    surfaceVariant = Color(0xFFF2F2F5),
    error = Color(0xFFB3261E),
)

@Composable
fun NomadTheme(forceDark: Boolean, content: @Composable () -> Unit) {
    val dark = forceDark || isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

/** The card colour behind tiles and rows (secondarySystemBackground on iOS). */
val cardColor: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant

val accent: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
