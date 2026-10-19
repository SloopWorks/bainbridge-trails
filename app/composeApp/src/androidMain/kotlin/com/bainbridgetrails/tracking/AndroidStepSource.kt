package com.bainbridgetrails.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build

/** TYPE_STEP_COUNTER, converted to session-relative counts by [StepBaseline]. */
class AndroidStepSource(context: Context) : StepSource {
    private val context = context.applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var listener: SensorEventListener? = null

    override val available: Boolean
        get() = sensor != null && hasPermission()

    override fun start(onSample: (StepSample) -> Unit) {
        val s = sensor ?: return
        if (listener != null || !hasPermission()) return
        val baseline = StepBaseline()
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val steps = baseline.relative(event.values[0])
                onSample(StepSample(AndroidMappers.elapsedToEpochMs(event.timestamp), steps))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = l
        sensorManager.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun stop() {
        val l = listener ?: return
        listener = null
        sensorManager.unregisterListener(l)
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
}
