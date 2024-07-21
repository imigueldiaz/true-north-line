package org.imigueldiaz.northline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton


class MainActivity : BaseActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        super.onCreate(savedInstanceState)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
    }

    override fun initializeUIComponents() {

        super.initializeUIComponents()

        val cameraButton: FloatingActionButton = findViewById(R.id.cameraButton)
        cameraButton.setOnClickListener {
            checkAndStartCustomCameraActivity()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
    }

    override fun onProviderDisabled(provider: String) {
    }

    override fun onProviderEnabled(provider: String) {
    }

    // Step 3: Handle Permission Request Result in MainActivity
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted, start CustomCameraActivity
                startCustomCameraActivity()
            } else {
                // Permission denied, show a message to the user
                Toast.makeText(
                    this, "Camera permission is required to use the camera", Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun checkAndStartCustomCameraActivity() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            // Permission has already been granted, start CustomCameraActivity
            startCustomCameraActivity()
        }
    }

    private fun startCustomCameraActivity() {
        val intent = Intent(this, CustomCameraActivity::class.java)
        startActivity(intent)
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 2
    }

}