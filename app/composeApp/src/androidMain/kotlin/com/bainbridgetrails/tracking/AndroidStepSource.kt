package com.bainbridgetrails.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * TYPE_STEP_COUNTER, converted to session-relative counts by [StepBaseline].
 *
 * The step counter may batch events in the sensor FIFO. [flush] asks the sensor to deliver everything pending;
 * Android delivers those events before `onFlushCompleted`, so a heartbeat emitted from there never claims
 * "no new steps" while steps are still sitting in the FIFO.
 */
class AndroidStepSource(context: Context) : StepSource {
    private val context = context.applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    @Volatile private var listener: SensorEventListener2? = null

    private class PendingFlush(val onFlushed: () -> Unit) {
        val done = CompletableDeferred<Unit>()
    }

    private val pending = AtomicReference<PendingFlush?>(null)

    override val available: Boolean
        get() = sensor != null && hasPermission()

    override fun start(onSample: (StepSample) -> Unit) {
        val s = sensor ?: return
        if (listener != null || !hasPermission()) return
        val baseline = StepBaseline()
        val l = object : SensorEventListener2 {
            override fun onSensorChanged(event: SensorEvent) {
                val steps = baseline.relative(event.values[0])
                onSample(StepSample(AndroidMappers.elapsedToEpochMs(event.timestamp), steps))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onFlushCompleted(sensor: Sensor?) {
                // Only the flush still being waited for (not timed out) emits, and at most once.
                val p = pending.getAndSet(null) ?: return
                p.onFlushed()
                p.done.complete(Unit)
            }
        }
        listener = l
        sensorManager.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /**
     * Flushes the sensor FIFO and waits up to [timeoutMs] for `onFlushCompleted`, which calls [onFlushed] on the
     * listener thread after every batched event was delivered. Returns false, without calling [onFlushed], when
     * not started, when the flush is refused, or when it does not complete in time. One flush at a time.
     */
    suspend fun flush(timeoutMs: Long, onFlushed: () -> Unit): Boolean {
        val l = listener ?: return false
        val p = PendingFlush(onFlushed)
        pending.set(p)
        try {
            if (!sensorManager.flush(l)) return false
            return withTimeoutOrNull(timeoutMs) { p.done.await() } != null
        } finally {
            pending.compareAndSet(p, null)
        }
    }

    override fun stop() {
        val l = listener ?: return
        listener = null
        pending.set(null)
        sensorManager.unregisterListener(l)
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
}
