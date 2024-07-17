package org.imigueldiaz.northline


import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class NorthLineView : View {
    private var paint: Paint? = null
    private var azimuth = 0f


    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context?) : super(context) {
        init()
    }

    private fun init() {
        paint = Paint()
        paint!!.color = Color.BLUE
        paint!!.strokeWidth = 10f
        paint!!.alpha = 70
    }

    fun setAzimuth(azimuth: Float, declination: Float) {
        this.azimuth = azimuth

        val isDeclinationZero = abs(azimuth) <= declination
        paint?.color = if (isDeclinationZero) Color.GREEN else Color.BLUE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint!!.alpha = 70
        val width = width
        val height = height
        val centerX = (width / 2).toFloat()
        val centerY = (height / 2).toFloat()
        val endX = (centerX + sin(Math.toRadians(azimuth.toDouble())) * centerY).toFloat()
        val endY = (centerY - cos(Math.toRadians(azimuth.toDouble())) * centerY).toFloat()
        canvas.drawLine(centerX, centerY, endX, endY, paint!!)
    }
}