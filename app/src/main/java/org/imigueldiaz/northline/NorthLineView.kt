package org.imigueldiaz.northline


import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.View
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NorthLineView : View {


    private var azimuth = 0f
    private var declination = 0f
    private val paint: Paint by lazy {
        Paint().apply {
            setARGB(190, 64, 64, 64) // Set the color to DKGRAY with an alpha of 190
            strokeWidth = 10f
        }
    }

    private var cachedStrokeWidth: Int = 0
    private var cachedCameraFOV: Float? = null
    private var cachedDeclination: Float = 0f

    private var cameraId: String? = null

    fun setCameraId(cameraId: String) {
        this.cameraId = cameraId
    }

    fun getCameraFOV(): Float? {
        return cachedCameraFOV
    }


    constructor(context: Context?) : super(context)

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        val azimuthRadians = Math.toRadians(azimuth.toDouble())

        val endX = centerX + sin(azimuthRadians) * centerY
        val endY = centerY - cos(azimuthRadians) * centerY

        canvas.drawLine(centerX, centerY, endX.toFloat(), endY.toFloat(), paint)
    }

    fun setAzimuth(azimuth: Float) {
        this.azimuth = azimuth

        val isDeclinationZero = abs(azimuth) <= declination
        paint.color = if (isDeclinationZero) Color.GREEN else Color.BLUE

        invalidate()
    }

    fun setDeclination(declination: Float) {
        this.declination = declination
        cachedDeclination = 0f // Invalidar el valor en caché de la declinación
        val width = calculateStrokeWidth()
        Log.d("NorthLineView", "Declination: $declination, Width: $width")
        paint.strokeWidth = width.toFloat()
        invalidate()
    }

    // calculate stroke width based on declination knowing that it is the separation between true north and magnetic north
    private fun calculateStrokeWidth(): Int {
        val currentDeclination = declination
        if (cachedDeclination == currentDeclination && cachedCameraFOV != null) {
            return cachedStrokeWidth
        }

        val cameraFOV = getCameraFOV(context) ?: run {
            cachedStrokeWidth = 10
            return cachedStrokeWidth
        }

        val declinationIntPart = ceil(currentDeclination)
        val strokeWidth = ceil(2 * width * abs(sin(Math.toRadians(cameraFOV / 2.0 * declinationIntPart))) / 180).toInt()
        Log.d("NorthLineView", "Camera FOV: $cameraFOV, Declination Int Part: $declinationIntPart, Stroke Width: $strokeWidth")

        cachedStrokeWidth = strokeWidth
        cachedDeclination = currentDeclination
        cachedCameraFOV = cameraFOV

        return strokeWidth
    }

    private fun getCameraFOV(context: Context): Float? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rotation = display.rotation
        val isVertical = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180

        try {
            if (cameraId != null) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focalLengths != null && sensorSize != null && focalLengths.isNotEmpty()) {
                    val focalLength = focalLengths[0]
                    val sensorWidth = sensorSize.width
                    val sensorHeight = sensorSize.height

                    // Calculate the crop factor based on sensor dimensions
                    val referenceSensorDiagonal = 43.27 // Example value for a full-frame sensor (36mm x 24mm)
                    val cameraSensorDiagonal = sqrt(sensorWidth * sensorWidth + sensorHeight * sensorHeight)
                    val cropFactor = referenceSensorDiagonal / cameraSensorDiagonal

                    // Calculate the horizontal or vertical field of view based on the device orientation
                    val fov = if (isVertical) {
                        2 * atan((sensorHeight / (2 * focalLength * cropFactor)).toDouble())
                    } else {
                        2 * atan((sensorWidth / (2 * focalLength * cropFactor)).toDouble())
                    }
                    return Math.toDegrees(fov).toFloat()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }



}