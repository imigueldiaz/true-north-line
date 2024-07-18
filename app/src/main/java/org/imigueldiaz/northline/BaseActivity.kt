package org.imigueldiaz.northline

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

abstract class BaseActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private var declination = Float.NaN
    private var vibrator: Vibrator? = null
    private lateinit var soundPool: SoundPool
    private var soundID: Int = 0
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var northLineView: NorthLineView? = null
    private var declinationTextView: TextView? = null
    private var accuracyTextView: TextView? = null
    protected var rotationVectorSensor: Sensor? = null
    private var toneGenerator: ToneGenerator? = null
    protected val sensorManager: SensorManager by lazy {
        getSystemService(SENSOR_SERVICE) as SensorManager
    }

    protected val locationManager: LocationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private val vibratorManager: VibratorManager by lazy {
        getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
    }

    companion object {
        protected const val LOCATION_PERMISSION_REQUEST_CODE = 1
        protected const val CAMERA_PERMISSION_REQUEST_CODE = 2
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions if not already granted
        requestPermissionsIfNeeded()

        // Initialize SoundPool
        initializeSoundPool()

        // Initialize UI components
        initializeUIComponents()

        // Start location updates
        startLocationUpdates()

    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // 5 seconds
                10f, // 10 meters
                this
            )
        } else {
            // Request permissions if not already granted
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    protected fun getCameraFOV(context: Context): Float? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (focalLengths != null && sensorSize != null && focalLengths.isNotEmpty()) {
                        val focalLength = focalLengths[0]
                        val sensorWidth = sensorSize.width
                        // Calculate the horizontal field of view
                        val fovHorizontal = 2 * Math.atan((sensorWidth / (2 * focalLength)).toDouble())
                        return Math.toDegrees(fovHorizontal).toFloat()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    protected open fun initializeUIComponents() {
        northLineView = findViewById(R.id.northLineView)
        accuracyTextView = findViewById(R.id.accuracyTextView)
        declinationTextView = findViewById(R.id.declinationTextView)

        rotationVectorSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        vibrator = vibratorManager.defaultVibrator
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        northLineView = findViewById(R.id.northLineView)
    }

    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build()

        // Load the sound (assuming you have a sound file in res/raw/ping.mp3)
        soundID = soundPool.load(this, R.raw.ping, 1)
    }

    private fun requestPermissionsIfNeeded() {
        // Check and request for location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.VIBRATE),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // Check and request for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
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

    /**
     * Play a tone when the azimuth is exactly equal to the declination
     */
    private fun exactPing() {
        soundPool.play(soundID, 1f, 1f, 0, 0, 1f)
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

        return String.format("%s° %d' %s\"", degreesFormatted, minutes, String.format("%.2f", seconds))
    }



    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

            //Log.d("Azimuth", azimuth.toString())

            if (!declination.isNaN()) {
                azimuth = (azimuth + declination + 360) % 360
                runOnUiThread {
                    northLineView?.setAzimuth(azimuth, declination)
                }

                if ((abs(azimuth) < declination || abs(azimuth - 360) < declination) && declination > 0) {
                    vibrate()
                } else if (abs(azimuth - declination) <= 0.1f) {
                    exactPing()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Implement in child classes if needed
    }

    override fun onLocationChanged(location: Location) {
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
    }

    private fun updateLocationUI(location: Location) {
        val latitude = location.latitude
        val longitude = location.longitude
        val altitude = location.altitude

        val latitudeDMS = convertToDMS(latitude)
        val longitudeDMS = convertToDMS(longitude)
        val altitudeMeters = String.format("%.2f m", altitude)

        val locationAltitudeText = "Lat: $latitudeDMS, Long: $longitudeDMS, Alt: $altitudeMeters"
        findViewById<TextView>(R.id.locationAltitudeTextView).text = locationAltitudeText

        // Set OnClickListener to copy data to clipboard
        handleLocationDataClick(locationAltitudeText)
    }

    private fun handleLocationDataClick(locationAltitudeText: String) {
        findViewById<TextView>(R.id.locationAltitudeTextView).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Location Data", locationAltitudeText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Update the declination UI
     */
    private fun updateDeclinationUI() {
        val declinationDirection = if (declination > 0) "E" else "O"
        val declinationText = if (!declination.isNaN()) {
            String.format(getString(R.string.MAGNETIC_DECLINATION) + " $declinationDirection", declination)
        } else {
            String.format(getString(R.string.MAGNETIC_DECLINATION_NOT_CALCULATED))
        }
        declinationTextView?.text = declinationText
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
}