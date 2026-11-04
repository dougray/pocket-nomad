// MeshLocation.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// GPS for the coverage scanner, drive mode and SOS, using Android's own
// LocationManager (no Google Play Services). Port of MeshLocation.swift plus
// the parts of the iOS LocationService the mesh code uses. Users call
// begin()/end() in pairs; updates run while anyone needs them.

package org.nomadpocket.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class MeshLocation(context: Context) {
    private val context = context.applicationContext
    private val manager: LocationManager? = this.context.getSystemService(LocationManager::class.java)
    private val _fix = MutableStateFlow<Fix?>(null)
    /** The newest fix while updates run (may be stale; check its time). */
    val fixFlow: StateFlow<Fix?> = _fix.asStateFlow()
    val fix: Fix? get() = _fix.value

    private var users = 0
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Needed below API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** True when location is switched off for the whole phone. */
    val isLocationOff: Boolean
        get() = manager?.let { !(it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) } ?: true

    fun begin() {
        users += 1
        refresh()
    }

    fun end() {
        users = maxOf(0, users - 1)
        if (users == 0) stop()
    }

    /** Starts updates if someone needs them and permission has since been granted. */
    @SuppressLint("MissingPermission")
    fun refresh() {
        if (users == 0 || running || manager == null || !hasPermission()) return
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!manager.allProviders.contains(provider)) continue
                manager.getLastKnownLocation(provider)?.let { accept(it) }
                manager.requestLocationUpdates(provider, 2_000L, 0f, listener, Looper.getMainLooper())
            }
            running = true
        } catch (_: SecurityException) {
            running = false
        }
    }

    private fun stop() {
        if (!running) return
        running = false
        try { manager?.removeUpdates(listener) } catch (_: SecurityException) {}
    }

    private fun accept(l: Location) {
        val new = Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toDouble() else -1.0, l.time)
        val cur = _fix.value
        // Prefer a newer fix unless it is much worse and the current one is still recent.
        if (cur != null && new.timeMillis - cur.timeMillis < 10_000 && cur.accuracy in 0.0..new.accuracy && new.accuracy > 100) return
        _fix.value = new
    }

    /**
     * One fix for an SOS: a recent good one if there is one, otherwise waits up to
     * `timeoutMillis` for GPS. Null when permission is refused or nothing comes.
     */
    suspend fun currentLocation(timeoutMillis: Long = 15_000): Fix? {
        if (!hasPermission()) return null
        fun good(f: Fix?) = f != null && System.currentTimeMillis() - f.timeMillis < 60_000 && f.accuracy in 0.0..100.0
        if (good(fix)) return fix
        begin()
        try {
            return withTimeoutOrNull(timeoutMillis) { _fix.first { good(it) } } ?: fix
        } finally {
            end()
        }
    }
}
