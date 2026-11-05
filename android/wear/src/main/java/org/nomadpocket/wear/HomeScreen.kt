package org.nomadpocket.wear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** Like the Apple Watch home: a big SOS button, then Compass and First aid. */
@Composable
fun HomeScreen(onSos: () -> Unit, onCompass: () -> Unit, onFirstAid: () -> Unit) {
    val state = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = state) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ListHeader { Text("Pocket Nomad") } }
            item {
                Button(
                    onClick = onSos,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WearBrand.danger, contentColor = Color.White, secondaryContentColor = Color.White),
                    label = {
                        Text("SOS", Modifier.fillMaxWidth(), fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    },
                    secondaryLabel = {
                        Text("Signal light", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                    },
                )
            }
            item { MenuButton("Compass", Icons.Filled.LocationOn, onCompass) }
            item { MenuButton("First aid", Icons.Filled.Add, onFirstAid) }
            item {
                Text(
                    "Mesh SOS and messages need a phone link, which comes in a later version.",
                    Modifier.fillMaxWidth(),
                    color = WearBrand.secondaryText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MenuButton(title: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        icon = { Icon(icon, contentDescription = null, tint = WearBrand.amber) },
        label = { Text(title, fontWeight = FontWeight.SemiBold) },
    )
}
