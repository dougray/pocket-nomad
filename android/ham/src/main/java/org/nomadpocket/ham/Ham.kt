// Ham.kt
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// The app-wide HamService, created once from the Application.

package org.nomadpocket.ham

import android.content.Context
import kotlinx.coroutines.MainScope
import org.nomadpocket.mesh.Mesh

object Ham {
    private var _service: HamService? = null
    val service: HamService get() = _service ?: error("Ham.init(context) not called")
    val isInitialized get() = _service != null

    fun init(context: Context) {
        if (_service != null) return
        val app = context.applicationContext
        _service = HamService(
            transport = HamBluetoothTransport(app),
            settings = HamSettings(SharedHamPrefs(app), KeystoreSecretBox()),
            // No-backup storage: stations, messages and mail stay on this phone.
            dir = java.io.File(app.noBackupFilesDir, "ham"),
            scope = MainScope(),
            locate = {
                if (!Mesh.isInitialized) Mesh.init(app)
                Mesh.location.currentLocation()?.let { doubleArrayOf(it.latitude, it.longitude) }
            },
        )
    }
}
