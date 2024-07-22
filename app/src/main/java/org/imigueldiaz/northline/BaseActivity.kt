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


    private val soundPool: SoundPool by lazy { initializeSoundPool() }
    private var accuracyTextView: TextView? = null
    private var azimuthGlobal = Float.NaN
    private var cachedDeclination: Float = 0f
    private var declination = Float.NaN
    private var declinationTextView: TextView? = null
    private var globalLocation: Location? = null
    private var lastX: Float? = null
    private var lastY: Float? = null
    private var lastZ: Float? = null
    private var soundID: Int = 0
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private val northLineView: NorthLineView by lazy { findViewById(R.id.northLineView) }

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

        val latitude = location.latitude
        val longitude = location.longitude
        val altitude = location.altitude

        declination = GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            altitude.toFloat(),
            System.currentTimeMillis()
        ).declination

        //Log.d("Declination", declination.toString())

        runOnUiThread {
            //  Update the UI components
            updateLocationUI(location)
            updateDeclinationUI()
            updateAccuracyUI(location.accuracy)
        }

        northLineView.invalidate()

    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

            if (!declination.isNaN()) {
                azimuth = (azimuth + declination + 360) % 360
            }

            azimuthGlobal = azimuth

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
                    northLineView.invalidate()

                    if (declination != cachedDeclination) {
                        northLineView.setDeclination(declination)
                        cachedDeclination = declination
                    }


                    updateLocationUI(globalLocation)
                }

                if ((abs(azimuth) < declination || abs(azimuth - 360) < declination) && declination > 0) {
                    vibrate()
                } else if (abs(azimuth - declination) <= THRESHOLD) {
                    exactPing()
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

        // Adjust the values if seconds is equal to 60
        if (minutes >= 60) {
            degree += minutes / 60
            minutes %= 60
        }

        // Add sign to the degree
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

    /**
     * Play a tone when the azimuth is exactly equal to the declination
     */
    private fun exactPing() {

        if (soundID != 0) {
            soundPool.play(soundID, 1f, 1f, 0, 0, 1f)
        } else {
            Toast.makeText(this, "Error loading sound", Toast.LENGTH_SHORT).show()
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
                // Load the sound (assuming you have a sound file in res/raw/ping.mp3)
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
            // Request permissions if not already granted
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

    /**
     * Update the declination UI
     */
    private fun updateDeclinationUI() {
        val declinationDirection = if (declination > 0) "E" else "O"
        val declinationText = if (!declination.isNaN()) {
            String.format(
                getString(R.string.MAGNETIC_DECLINATION) + " $declinationDirection",
                declination
            )
        } else {
            String.format(getString(R.string.MAGNETIC_DECLINATION_NOT_CALCULATED))
        }
        declinationTextView?.text = declinationText
    }

    private fun updateLocationUI(location: Location?) {

        if (location == null) {
            return
        }

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
        if (cameraFov == null) {
            cameraFov = 0f
        }

        val formattedCameraFov = String.format(Locale.getDefault(), "%.1f", cameraFov)

        val locationAltitudeText =
            "Lat: $latitudeDMS ($latitudeDec) , Long: $longitudeDMS ($longitudeDec) \n Alt: $altitudeMeters \n FOV: $formattedCameraFov \n  North: $formattedDifferenceToNorth°"
        val spannableString = SpannableString(locationAltitudeText)

        // Find the start index of "North: $formattedDifferenceToNorth°"
        val northStartIndex = locationAltitudeText.indexOf("North:")
        // Assuming "North: $formattedDifferenceToNorth°" is at the end, its end index is the length of the string
        val northEndIndex = locationAltitudeText.length

        // Apply bold style
        spannableString.setSpan(
            StyleSpan(Typeface.BOLD),
            northStartIndex,
            northEndIndex,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE
        )
        // Increase the size by 1.5 times
        spannableString.setSpan(
            RelativeSizeSpan(1.5f),
            northStartIndex,
            northEndIndex,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE
        )

        // Set the styled text to the TextView
        findViewById<TextView>(R.id.locationAltitudeTextView).text = spannableString

        // Set OnClickListener to copy data to clipboard
        handleLocationDataClick(locationAltitudeText)
    }

    private fun vibrate() {
        if (vibrator!!.hasVibrator()) {
            vibrator!!.vibrate(
                VibrationEffect.createOneShot(
                    100,
                    VibrationEffect.EFFECT_HEAVY_CLICK
                )
            )
        }
    }

    companion object {
        protected const val LOCATION_PERMISSION_REQUEST_CODE = 1

        // Sensitivity threshold for the azimuth
        private const val THRESHOLD = 0.00349f // 0.2 degrees
    }

}