// DriveModeService.kt
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// The foreground service that keeps drive mode alive with the screen off: an
// ongoing notification with a Stop action, GPS through LocationManager, and
// the radios' Bluetooth links kept up (both, with a Meshtastic and a MeshCore
// radio connected: the links live in Mesh, this service keeps the process in
// the foreground so neither is dropped). DriveMode starts and stops it;
// Drive mode runs until the user turns it off. When no radio has been
// connected for 5 minutes (DriveState.radioOutage) the ongoing notification
// says so and one alert notification goes out per outage; it is withdrawn
// when a radio connects or drive mode stops.

package org.nomadpocket.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DriveModeService : Service() {
    private var scope: CoroutineScope? = null
    private var holdingLocation = false
    private var wakeLock: PowerManager.WakeLock? = null
    /** The outage alert is showing (posted once per outage). */
    private var alertShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stopping drive mode calls stop() below through DriveMode's hook.
            if (Mesh.isInitialized) Mesh.drive.stop() else stopSelf()
            return START_NOT_STICKY
        }
        if (!Mesh.isInitialized || !Mesh.drive.state.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(Mesh.drive.state), foregroundTypes(this))
        } catch (e: Exception) {
            // Missing permission or started from the background: drive mode can't run.
            Mesh.drive.stop()
            return START_NOT_STICKY
        }
        if (!holdingLocation) {
            holdingLocation = true
            Mesh.location.begin()
        }
        // Keeps the ping loop ticking with the screen off; released on stop. Drive
        // mode has no time limit, so the lock is renewed on every notification update.
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketNomad:DriveMode")
                ?.apply { setReferenceCounted(false); acquire(WAKE_LOCK_MILLIS) }
        }
        if (scope == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = s
            s.launch {
                val links = combine(Mesh.radios.links.values.map { it.displayConnectionFlow }) { radiosLine() }
                combine(Mesh.drive.stateFlow, Mesh.coverage.samplesFlow, links) { st, samples, line -> Triple(st, samples.size, line) }
                    .collect { (st, n, line) ->
                        if (st.active) {
                            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(st, n, line))
                            wakeLock?.acquire(WAKE_LOCK_MILLIS) // renew: drive mode has no time limit
                        }
                        showOutageAlert(st.active && st.radioOutage)
                    }
            }
        }
        return START_NOT_STICKY // after a process death drive mode is off; don't come back half-started
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        showOutageAlert(false)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (holdingLocation) {
            holdingLocation = false
            if (Mesh.isInitialized) Mesh.location.end()
        }
        super.onDestroy()
    }

    /** Posts the outage alert once when an outage starts; withdraws it when it ends. */
    private fun showOutageAlert(show: Boolean) {
        if (show == alertShown) return
        alertShown = show
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (!show) { nm.cancel(ALERT_ID); return }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 3, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val alert = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.mesh_ic_drive)
            .setContentTitle(DriveMode.OUTAGE_ALERT_TITLE)
            .setContentText(DriveMode.OUTAGE_ALERT_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(DriveMode.OUTAGE_ALERT_TEXT))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // Without notification permission (Android 13+) this is dropped; the ongoing notification still says it.
        try { nm.notify(ALERT_ID, alert) } catch (_: SecurityException) {}
    }

    /** "Meshtastic connected · MeshCore waiting", for the radios in use. */
    private fun radiosLine(): String = Mesh.radios.inUse.joinToString(" · ") { (fw, link) ->
        fw.displayName + " " + when (link.displayConnection) {
            is MeshConnectionState.Connected -> "connected"
            is MeshConnectionState.Syncing, is MeshConnectionState.Connecting -> "connecting"
            is MeshConnectionState.NotNearby -> "not nearby"
            is MeshConnectionState.NotWithMe -> "not with me"
            else -> "waiting"
        }
    }.ifEmpty { "No radio connected" }

    private fun notification(st: DriveState, points: Int = 0, radios: String = radiosLine()): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, DriveModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val text = buildString {
            if (st.radioOutage) append("${DriveMode.OUTAGE_NOTE} · ")
            append("Recording coverage: $points points")
            if (st.sent > 0) append(" · ${st.sent} pings (${st.reached} answered, ${st.relayed} repeated, ${st.unanswered} nothing)")
            st.pingNote?.let { append(" · $it") }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.mesh_ic_drive)
            .setContentTitle(if (Mesh.radios.inUse.size > 1) "Drive mode on · both radios" else "Drive mode on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$radios\nRuns until you tap Stop."))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        /** How long one wake-lock hold lasts; renewed on every notification update while driving. */
        private const val WAKE_LOCK_MILLIS = 2 * 3600 * 1000L
        const val ACTION_STOP = "org.nomadpocket.mesh.action.STOP_DRIVE"
        private const val CHANNEL_ID = "mesh.drive"
        private const val NOTIFICATION_ID = 4107
        private const val ALERT_CHANNEL_ID = "mesh.drive.alert"
        private const val ALERT_ID = 4108

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Drive mode", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while drive mode records coverage."
                })
            }
            if (nm.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "Drive mode alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When drive mode is running but no radio has connected for 5 minutes."
                })
            }
        }

        /** location always; connectedDevice only when Bluetooth permission is granted (Android 14 checks). */
        private fun foregroundTypes(context: Context): Int {
            if (Build.VERSION.SDK_INT < 29) return 0
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            val bt = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (bt) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            return types
        }

        internal fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DriveModeService::class.java))
            } catch (_: Exception) {
                // Not allowed right now (for example, started from the background).
                android.os.Handler(android.os.Looper.getMainLooper()).post { if (Mesh.isInitialized) Mesh.drive.stop() }
            }
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, DriveModeService::class.java))
        }
    }
}
