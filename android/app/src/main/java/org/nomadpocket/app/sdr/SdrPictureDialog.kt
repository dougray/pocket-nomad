// SdrPictureDialog.kt
// Pocket Nomad - SDR module
// SPDX-License-Identifier: Apache-2.0

package org.nomadpocket.app.sdr

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.nomadpocket.i18n.tr

/** One weather-satellite picture from the box, fetched over Wi-Fi. */
@Composable
fun SdrPictureDialog(image: SdrImage, host: String, token: String, onDismiss: () -> Unit) {
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(image.id) { bytes = SdrClient.fetchImage(host, token, image); failed = bytes == null }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(image.sat) },
        text = {
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            when {
                bmp != null -> Image(bmp.asImageBitmap(), image.sat, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentScale = ContentScale.FillWidth)
                failed -> Text(tr("Couldn't get the picture. Pictures come over Wi-Fi from the box: check the address and pairing token."))
                else -> CircularProgressIndicator()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } })
}
