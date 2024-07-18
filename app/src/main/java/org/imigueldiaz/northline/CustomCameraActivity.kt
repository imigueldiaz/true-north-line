package org.imigueldiaz.northline

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
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
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

import kotlin.math.abs

class CustomCameraActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val CAMERA_PERMISSION_REQUEST_CODE = 2
    }

    private lateinit var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var declination = Float.NaN
    private var sensorManager: SensorManager? = null
    private var rotationVectorSensor: Sensor? = null
    private var locationManager: LocationManager? = null
    private var northLineView: NorthLineView? = null
    private var accuracyTextView: TextView? = null
    private var declinationTextView: TextView? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null
    private val vibratorManager: VibratorManager by lazy {
        getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
    }


    private lateinit var soundPool: SoundPool
    private var soundID: Int = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_camera)

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

        northLineView = findViewById(R.id.northLineView)

        textureView = findViewById(R.id.cameraTextureView)
        textureView.surfaceTextureListener = surfaceTextureListener

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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.VIBRATE), LOCATION_PERMISSION_REQUEST_CODE)
        }

        // Check and request for camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        }
    }

    private val surfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Transform your image captured size according to the surface width and height
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
                return
            }
            manager.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture ?: return
            texture.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)

            captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder?.addTarget(surface)

            cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return

                    cameraCaptureSession = session
                    try {
                        captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        cameraCaptureSession?.setRepeatingRequest(captureRequestBuilder?.build()!!, null, null)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager!!.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onPause() {
        super.onPause()
        sensorManager!!.unregisterListener(this)
        locationManager!!.removeUpdates(this)
        closeCamera()
    }

    override fun onStop() {
        super.onStop()
        closeCamera()
    }

    private fun closeCamera() {
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
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
        val degreesAbs = abs(degrees)
        var degree = degreesAbs.toInt()
        var minutesFull = (degreesAbs - degree) * 60
        var minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60

        // Ajustar grados y minutos si los minutos son >= 60
        if (minutes >= 60) {
            degree += minutes / 60
            minutes %= 60
        }

        // Añadir signo a los grados basado en el valor original
        val degreesFormatted = if (degrees < 0) "-$degree" else "$degree"

        return String.format("%s° %d' %s\"", degreesFormatted, minutes, String.format("%.2f", seconds))
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
