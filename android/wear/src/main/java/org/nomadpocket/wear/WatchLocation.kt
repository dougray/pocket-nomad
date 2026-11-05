package org.nomadpocket.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's own GPS and compass, through Android's LocationManager and
 * SensorManager (no Google Play Services). Runs only while a screen that needs
 * it is open. Like Watch/Sources/WatchLocation.swift.
 */
object WatchLocation {
    /** [degrees] from true north when [trueNorth], else from magnetic north (no fix yet for the declination). */
    data class Reading(val degrees: Double, val trueNorth: Boolean)

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()
    private val _heading = MutableStateFlow<Reading?>(null)
    val heading: StateFlow<Reading?> = _heading.asStateFlow()
    private val _permitted = MutableStateFlow(false)
    val permitted: StateFlow<Boolean> = _permitted.asStateFlow()

    var askedThisRun = false
    private var users = 0
    private var appContext: Context? = null
    private var listening = false
    private var magnetic: Double? = null

    private val listener = LocationListener { loc -> _location.value = loc }

    private val sensorListener = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val orientation = FloatArray(3)
        override fun onSensorChanged(event: SensorEvent) {
            // A watch face doesn't rotate, so no display remapping is needed.
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            val m = Heading.fromAzimuth(orientation[0].toDouble())
            // Like iOS's 1° heading filter: ignore jitter under a degree.
            val old = magnetic
            if (old != null && Heading.diff(old, m) < 1) return
            magnetic = m
            val loc = _location.value
            _heading.value = if (loc != null) {
                val field = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis())
                Reading(Heading.trueHeading(m, field.declination.toDouble()), trueNorth = true)
            } else Reading(m, trueNorth = false)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun compassSensor(sensors: SensorManager): Sensor? =
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    fun hasCompass(context: Context): Boolean =
        compassSensor(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager) != null

    /** Many Wear OS watches have no GPS chip; then a fix can only come from the system's other providers. */
    fun hasGps(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    /** Call when a screen that needs location appears; pair with [end]. */
    fun begin(context: Context) {
        appContext = context.applicationContext
        users += 1
        start()
    }

    fun end() {
        users = maxOf(0, users - 1)
        if (users == 0) stop()
    }

    fun permissionChanged() {
        if (users > 0) {
            stop()
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        val ctx = appContext ?: return
        _permitted.value = hasPermission(ctx)
        if (listening) return
        val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        compassSensor(sensors)?.let { sensors.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI) }
        if (_permitted.value) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            // The platform's own providers; "fused" here is Android's, not Play Services'.
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (!lm.allProviders.contains(provider)) continue
                runCatching {
                    lm.getLastKnownLocation(provider)?.let { if (_location.value == null) _location.value = it }
                    lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                }
            }
        }
        listening = true
    }

    private fun stop() {
        val ctx = appContext ?: return
        if (!listening) return
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(sensorListener)
        runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener) }
        listening = false
        magnetic = null
    }
}

/** Keeps location and the compass running while the calling screen is shown; asks for permission once per run. */
@Composable
fun KeepLocationRunning() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        WatchLocation.permissionChanged()
    }
    LaunchedEffect(Unit) {
        if (!WatchLocation.hasPermission(context) && !WatchLocation.askedThisRun) {
            WatchLocation.askedThisRun = true
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    DisposableEffect(Unit) {
        WatchLocation.begin(context)
        onDispose { WatchLocation.end() }
    }
}
