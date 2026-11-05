package org.nomadpocket.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** The phone's dark/amber look (android/app ui/Theme.kt), for the watch. Watches are always dark. */
object WearBrand {
    val amber = Color(0xFFFC9712)
    val orange = Color(0xFFFC840C)
    val highlight = Color(0xFFFEBB1E)
    val danger = Color(0xFFF24236)
    val card = Color(0xFF242424)
    val secondaryText = Color(0xFFA9ADB3)
}

private val Colors = ColorScheme(
    primary = WearBrand.amber,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2400),
    onPrimaryContainer = WearBrand.highlight,
    secondary = WearBrand.orange,
    onSecondary = Color.Black,
    tertiary = WearBrand.highlight,
    surfaceContainer = WearBrand.card,
    onSurface = Color(0xFFF2F2F2),
    onSurfaceVariant = WearBrand.secondaryText,
    error = WearBrand.danger,
    onError = Color.White,
    background = Color.Black,
    onBackground = Color(0xFFF2F2F2),
)

@Composable
fun WearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
