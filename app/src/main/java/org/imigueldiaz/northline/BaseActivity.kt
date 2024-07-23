package org.imigueldiaz.northline

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

abstract class BaseActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private var accuracyTextView: TextView? = null
    private var azimuthGlobal = Float.NaN
    private var cachedDeclination: Float = 0f
    private var declination = Float.NaN
    private var declinationTextView: TextView? = null
    private var globalLocation: Location? = null
    private var lastX: Float? = null
    private var lastY: Float? = null
    private var lastZ: Float? = null
    private var pitchGlobal = Float.NaN
    private var soundID: Int = 0
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private val northLineView: NorthLineView by lazy { findViewById(R.id.northLineView) }
    private val polarisLineView: View by lazy { findViewById(R.id.polarisLineView) }
    private val soundPool: SoundPool by lazy { initializeSoundPool() }

    private val vibratorManager: VibratorManager by lazy {
        getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
    }

    private val orientationAngles = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    protected var rotationVectorSensor: Sensor? = null

    protected val locationManager: LocationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }

    protected val sensorManager: SensorManager by lazy {
        getSystemService(SENSOR_SERVICE) as SensorManager
    }

    protected val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions if not already granted
        scope.launch {
            requestPermissionsIfNeeded()
        }

        // Initialize UI components
        initializeUIComponents()

        // Start location updates
        startLocationUpdates()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Implement in child classes if needed
    }

    override fun onLocationChanged(location: Location) {
        globalLocation = location
        updateGeomagneticField(location)

        // Force update sensor data if null
        if (lastX == null || lastY == null || lastZ == null) {
            forceSensorUpdate()
        }

        runOnUiThread {
            updateAllUI(location)
        }

        northLineView.invalidate()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

            if (!cachedDeclination.isNaN()) {
                azimuth = (azimuth + cachedDeclination + 360) % 360
            }

            azimuthGlobal = azimuth

            val pitchRadians = orientationAngles[1] // Pitch in radians
            pitchGlobal = Math.toDegrees(pitchRadians.toDouble()).toFloat() // Convert to degrees

            // Force update location data if null
            if (globalLocation == null) {
                forceLocationUpdate()
            }

            Log.d(
                TAG,
                "Orientation Angles: Azimuth: ${Math.toDegrees(orientationAngles[0].toDouble())}, Pitch: $pitchGlobal, Roll: ${
                    Math.toDegrees(orientationAngles[2].toDouble())
                }"
            )

            if (lastX == null || lastY == null || lastZ == null) {
                lastX = orientationAngles[0]
                lastY = orientationAngles[1]
                lastZ = orientationAngles[2]
                return
            }

            val deltaX = abs(orientationAngles[0] - lastX!!)
            val deltaY = abs(orientationAngles[1] - lastY!!)
            val deltaZ = abs(orientationAngles[2] - lastZ!!)

            if (deltaX > THRESHOLD || deltaY > THRESHOLD || deltaZ > THRESHOLD) {
                lastX = orientationAngles[0]
                lastY = orientationAngles[1]
                lastZ = orientationAngles[2]

                runOnUiThread {
                    northLineView.setAzimuth(azimuth)

                    // Update UI only if declination changes
                    if (declination != cachedDeclination) {
                        northLineView.setDeclination(declination)
                        cachedDeclination = declination
                    }

                    updateAllUI(globalLocation!!)

                    if ((abs(azimuth) < declination || abs(azimuth - 360) < declination) && declination > 0) {
                        vibrate()
                    } else if (abs(azimuth - declination) <= THRESHOLD) {
                        exactPing()
                    }
                }
            }
        }


    }

    protected open fun initializeUIComponents() {
        accuracyTextView = findViewById(R.id.accuracyTextView)
        declinationTextView = findViewById(R.id.declinationTextView)

        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        vibrator = vibratorManager.defaultVibrator
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    }

    private fun convertToDMS(degrees: Double): String {
        val degreesAbs = abs(degrees)
        var degree = degreesAbs.toInt()
        val minutesFull = (degreesAbs - degree) * 60
        var minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60

        if (minutes >= 60) {
            degree += minutes / 60
            minutes %= 60
        }

        val degreesFormatted = if (degrees < 0) "-$degree" else "$degree"

        return String.format(
            Locale.getDefault(),
            "%s° %d' %s\"",
            degreesFormatted,
            minutes,
            String.format(Locale.getDefault(), "%.2f", seconds)
        )
    }

    private fun convertToDecimalDegrees(degrees: Double): String {
        return String.format(Locale.getDefault(), "%.2f°", degrees)
    }

    private fun exactPing() {
        if (soundID != 0) {
            soundPool.play(soundID, 1f, 1f, 0, 0, 1f)
        } else {
            Toast.makeText(this, "Error loading sound", Toast.LENGTH_SHORT).show()
        }
    }

    private fun forceLocationUpdate() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                onLocationChanged(it)
            }
        }
    }

    private fun forceSensorUpdate() {
        // Trigger a sensor update, this is highly dependent on the implementation specifics of your sensor manager
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun handleLocationDataClick(locationAltitudeText: String) {
        findViewById<TextView>(R.id.locationAltitudeTextView).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Location Data", locationAltitudeText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeSoundPool(): SoundPool {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        return SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build().apply {
                soundID = load(this@BaseActivity, R.raw.ping, 1)
            }
    }

    private suspend fun requestPermissionsIfNeeded() = withContext(Dispatchers.IO) {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this@BaseActivity,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(
                this@BaseActivity,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this@BaseActivity,
                permissionsToRequest.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L, // 5 seconds
                20f, // 20 meters
                this
            )
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Update the accuracy UI
     */
    private fun updateAccuracyUI(accuracy: Float) {
        accuracyTextView?.text = String.format(getString(R.string.GPS_ACCURACY), accuracy)
        when {
            accuracy > 30 -> {
                accuracyTextView?.setTextColor(Color.RED)
            }

            else -> {
                accuracyTextView?.setTextColor(Color.GREEN)
            }
        }
    }

    private fun updateAllUI(location: Location) {
        updateLocationUI(location)
        updateDeclinationUI()
        updateAccuracyUI(location.accuracy)
        updatePolarisLineVisibility()
        northLineView.invalidate()
    }

    private fun updateDeclinationUI() {
        val declinationDirection = if (cachedDeclination > 0) "E" else "W"
        val declinationText = if (!cachedDeclination.isNaN()) {
            String.format(
                getString(R.string.MAGNETIC_DECLINATION) + " %s",
                cachedDeclination,
                declinationDirection
            )
        } else {
            getString(R.string.MAGNETIC_DECLINATION_NOT_CALCULATED)
        }
        declinationTextView?.text = declinationText
    }

    private fun updateGeomagneticField(location: Location) {
        val geomagneticField = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        )
        declination = geomagneticField.declination
        cachedDeclination = declination
    }

    private fun updateLocationUI(location: Location?) {
        if (location == null) return

        val latitude = location.latitude
        val longitude = location.longitude
        val altitude = location.altitude

        val latitudeDMS = convertToDMS(latitude)
        val longitudeDMS = convertToDMS(longitude)
        val altitudeMeters = String.format(Locale.getDefault(), "%.2f m", altitude)

        val differenceToNorth = if (azimuthGlobal > 180) 360 - azimuthGlobal else azimuthGlobal
        val formattedDifferenceToNorth =
            String.format(Locale.getDefault(), "%.1f", differenceToNorth)

        val latitudeDec = convertToDecimalDegrees(latitude)
        val longitudeDec = convertToDecimalDegrees(longitude)
        var cameraFov = northLineView.getCameraFOV()
        if (cameraFov == null) cameraFov = 0f

        val formattedCameraFov = String.format(Locale.getDefault(), "%.1f", cameraFov)

        val locationAltitudeText =
            "Lat: $latitudeDMS ($latitudeDec) , Long: $longitudeDMS ($longitudeDec) \n Alt: $altitudeMeters \n FOV: $formattedCameraFov \n  North: $formattedDifferenceToNorth°"
        val spannableString = SpannableString(locationAltitudeText)

        // Apply styles
        val northStartIndex = locationAltitudeText.indexOf("North:")
        val northEndIndex = locationAltitudeText.length

        spannableString.setSpan(
            StyleSpan(Typeface.BOLD),
            northStartIndex,
            northEndIndex,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE
        )
        spannableString.setSpan(
            RelativeSizeSpan(1.5f),
            northStartIndex,
            northEndIndex,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE
        )

        findViewById<TextView>(R.id.locationAltitudeTextView).text = spannableString

        handleLocationDataClick(locationAltitudeText)
    }

    private fun updatePolarisLineVisibility() {
        globalLocation?.let {
            val polarisAngle = it.latitude.toFloat()
            Log.d(TAG, "Polaris Angle: $polarisAngle")
            Log.d(TAG, "Current Pitch: $pitchGlobal")
            Log.d(TAG, "Difference: ${abs(pitchGlobal - polarisAngle)}")

            if (abs(pitchGlobal - polarisAngle) < TOLERANCE_DEGREES) {
                polarisLineView.visibility = View.VISIBLE
                Log.d(TAG, "Polaris line visible")
            } else {
                polarisLineView.visibility = View.GONE
                Log.d(TAG, "Polaris line hidden")
            }
        }
    }

    private fun vibrate() {
        vibrator?.let {
            if (it.hasVibrator()) {
                it.vibrate(
                    VibrationEffect.createOneShot(
                        100,
                        VibrationEffect.EFFECT_HEAVY_CLICK
                    )
                )
            }
        }
    }

    companion object {
        protected const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val THRESHOLD = 0.00349f // 0.2 degrees
        private const val TOLERANCE_DEGREES = 0.2f
        private const val TAG = "BaseActivity"
    }

}
