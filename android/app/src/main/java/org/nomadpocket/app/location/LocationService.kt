package org.nomadpocket.app.location

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
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GPS works with no cell service, so position, heading and altitude are always
 * available outdoors. One shared listener feeds the compass, map and daylight tools.
 * Uses Android's own LocationManager (no Google Play Services).
 */
object LocationService {
    data class Heading(val magnetic: Double, val trueHeading: Double)

    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()
    private val _heading = MutableStateFlow<Heading?>(null)
    val heading: StateFlow<Heading?> = _heading.asStateFlow()
    private val _permitted = MutableStateFlow(false)
    val permitted: StateFlow<Boolean> = _permitted.asStateFlow()

    /** The permission dialog is shown at most once per run; Settings can change it later. */
    var askedThisRun = false
    private var users = 0
    private var appContext: Context? = null
    private var listening = false

    private val listener = LocationListener { loc -> _location.value = loc }

    private val sensorListener = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val remapped = FloatArray(9)
        private val orientation = FloatArray(3)
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            val (x, y) = when (displayRotation()) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
            SensorManager.getOrientation(remapped, orientation)
            val magnetic = (Math.toDegrees(orientation[0].toDouble()) + 360) % 360
            val loc = _location.value
            val trueHeading = if (loc != null) {
                val field = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis())
                (magnetic + field.declination + 360) % 360
            } else -1.0
            val old = _heading.value
            // Like iOS's 1° heading filter: ignore jitter under a degree.
            if (old == null || angleDiff(old.magnetic, magnetic) >= 1) _heading.value = Heading(magnetic, trueHeading)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun angleDiff(a: Double, b: Double): Double = Math.abs(((a - b + 540) % 360) - 180)

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        (appContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasCompass(context: Context): Boolean =
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

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

    /** After the permission dialog: start the listeners that a visible screen asked for. */
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
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (_permitted.value) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
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
    }
}

/** Keeps location (and the compass sensor) running while the calling screen is shown. */
@Composable
fun rememberLocation(): Location? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationService.permissionChanged()
        // The radio layer uses location too (coverage, drive mode).
        if (org.nomadpocket.mesh.Mesh.isInitialized) org.nomadpocket.mesh.Mesh.permissionsChanged()
    }
    LaunchedEffect(Unit) {
        // Ask once per app run, when a screen that uses location first opens.
        if (!LocationService.hasPermission(context) && !LocationService.askedThisRun) {
            LocationService.askedThisRun = true
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    DisposableEffect(Unit) {
        LocationService.begin(context)
        onDispose { LocationService.end() }
    }
    val loc by LocationService.location.collectAsState()
    return loc
}
