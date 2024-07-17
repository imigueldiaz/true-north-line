package org.imigueldiaz.northline

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.text.*
import com.google.android.material.floatingactionbutton.FloatingActionButton


class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var locationManager: LocationManager? = null
    private var northLineView: NorthLineView? = null
    private var accuracyTextView: TextView? = null
    private var declinationTextView: TextView? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var declination = Float.NaN
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null
    private val vibratorManager: VibratorManager by lazy {
        getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
    }

    private lateinit var soundPool: SoundPool
    private var soundID: Int = 0
    private var cameraFOV: Float? = 0f



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissionsIfNeeded()

        // Initialize SoundPool
        initializeSoundPool()

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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        }
    }


    private fun initializeUIComponents() {
        northLineView = findViewById(R.id.northLineView)
        accuracyTextView = findViewById(R.id.accuracyTextView)
        declinationTextView = findViewById(R.id.declinationTextView)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        vibrator = vibratorManager.defaultVibrator
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)


        val cameraButton: FloatingActionButton = findViewById(R.id.cameraButton)
        cameraButton.setOnClickListener {
            checkAndStartCustomCameraActivity()
        }
    }

    private fun initializeSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.VIBRATE), LOCATION_PERMISSION_REQUEST_CODE)
        }

        // Check and request for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    // Step 1 & 2: Check and Request Camera Permission in MainActivity
    private fun checkAndStartCustomCameraActivity() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            // Permission has already been granted, start CustomCameraActivity
            startCustomCameraActivity()
        }
    }

    // Step 3: Handle Permission Request Result in MainActivity
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted, start CustomCameraActivity
                startCustomCameraActivity()
            } else {
                // Permission denied, show a message to the user
                Toast.makeText(this, "Camera permission is required to use the camera", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCustomCameraActivity() {
        cameraFOV = getCameraFOV(this)
        val intent = Intent(this, CustomCameraActivity::class.java)
        startActivity(intent)
    }

    fun getCameraFOV(context: Context): Float? {
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

    override fun onResume() {
        super.onResume()
        sensorManager!!.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() {
        super.onPause()
        sensorManager!!.unregisterListener(this)
        locationManager!!.removeUpdates(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

            Log.d("Azimuth", azimuth.toString())

            if (!declination.isNaN()) {
                azimuth = (azimuth + declination + 360) % 360
                // Asegúrate de que este método actualiza la vista correctamente
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

        Log.d("Declination", declination.toString())

        runOnUiThread {
            // Actualiza la UI con la nueva ubicación y declinación
            updateLocationUI(location)
            updateDeclinationUI()
            updateAccuracyUI(location.accuracy)
        }
    }

    /**
     * Play a tone when the azimuth is exactly equal to the declination
     */
    private fun exactPing() {
        soundPool.play(soundID, 1f, 1f, 0, 0, 1f)
    }

    /**
     * Vibrate when the azimuth is within the declination range
     */
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

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}



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
        findViewById<TextView>(R.id.locationAltitudeTextView).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Location Data", locationAltitudeText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun convertToDMS(degrees: Double): String {
        val degree = degrees.toInt()
        val minutes = ((abs(degrees) - degree) * 60).toInt()
        val seconds = (((abs(degrees) - degree) * 60) - minutes) * 60

        return String.format("%d°%d'%s\"", degree, minutes, String.format("%.2f", seconds))
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

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val CAMERA_PERMISSION_REQUEST_CODE = 2
    }

}