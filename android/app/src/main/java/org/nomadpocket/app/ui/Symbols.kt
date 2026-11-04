package org.nomadpocket.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.graphics.vector.ImageVector

/** Material icons for the SF Symbol names used in the shared guide and checklist JSON. */
object Symbols {
    fun icon(sfSymbol: String): ImageVector = when (sfSymbol) {
        "cross.case.fill" -> Icons.Filled.MedicalServices
        "drop.fill" -> Icons.Filled.WaterDrop
        "tent.fill" -> Icons.Filled.Cabin
        "antenna.radiowaves.left.and.right" -> Icons.Filled.SettingsInputAntenna
        "backpack.fill" -> Icons.Filled.Backpack
        "car.fill" -> Icons.Filled.DirectionsCar
        "figure.walk" -> Icons.AutoMirrored.Filled.DirectionsWalk
        "hammer.fill" -> Icons.Filled.Handyman
        "house.fill" -> Icons.Filled.Home
        "radio.fill" -> Icons.Filled.Radio
        "toilet.fill" -> Icons.Filled.Wc
        "location.north.circle.fill" -> Icons.Filled.Explore
        "flashlight.on.fill" -> Icons.Filled.FlashlightOn
        "dot.radiowaves.left.and.right" -> Icons.Filled.Contactless
        "heart.text.square.fill" -> Icons.Filled.LocalHospital
        "checklist" -> Icons.Filled.Checklist
        "sun.horizon.fill" -> Icons.Filled.WbTwilight
        "shippingbox.fill" -> Icons.Filled.Inventory2
        else -> Icons.Filled.Description
    }
}
