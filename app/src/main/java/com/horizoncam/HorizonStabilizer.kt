package com.horizoncam

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

/**
 * Roll-only horizon stabilizer using complementary filter (gyro + accelerometer).
 *
 * Provides an absolute roll angle that the GL renderer uses to counter-rotate
 * the camera feed, keeping the horizon level at any phone orientation.
 *
 * Hand-shake jitter (pitch/yaw) is handled by the camera hardware's OIS and
 * Android video stabilization — software double-integration of accelerometer
 * data is unreliable for real-time use and has been intentionally removed.
 */
class HorizonStabilizer(private val sensorManager: SensorManager) : SensorEventListener {

    /** Absolute roll angle in radians. */
    @Volatile var rollAngle: Float = 0f

    private val gyro  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private val GRAVITY_LP  = 0.8f      // low-pass weight for gravity estimate
    private val GYRO_WEIGHT = 0.98f     // gyro trust in complementary filter

    private var lastGyroNs: Long = 0
    private var firstAccelDone = false

    fun start() {
        reset()
        gyro?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    fun reset() {
        rollAngle = 0f
        lastGyroNs = 0
        firstAccelDone = false
        gravity[0] = 0f; gravity[1] = 0f; gravity[2] = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE     -> handleGyro(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccel(event)
        }
    }

    private fun handleGyro(event: SensorEvent) {
        if (!firstAccelDone) return
        if (lastGyroNs == 0L) { lastGyroNs = event.timestamp; return }
        val dt = (event.timestamp - lastGyroNs) * 1e-9f
        lastGyroNs = event.timestamp

        // Skip impossible dt values (suspend/resume glitches)
        if (dt <= 0f || dt > 0.5f) return

        // Gyro prediction: integrate Z-axis angular velocity (roll)
        val gyroPredicted = rollAngle + event.values[2] * dt

        // Gravity-based absolute roll reference
        val gravRoll = atan2(-gravity[0].toDouble(), gravity[1].toDouble()).toFloat()

        // Shortest angular difference avoids atan2 discontinuity at ±180°
        var correction = gravRoll - gyroPredicted
        while (correction > Math.PI)  correction -= (2.0 * Math.PI).toFloat()
        while (correction < -Math.PI) correction += (2.0 * Math.PI).toFloat()

        // Complementary filter: trust gyro short-term, correct drift with accel
        rollAngle = gyroPredicted + (1f - GYRO_WEIGHT) * correction
    }

    private fun handleAccel(event: SensorEvent) {
        if (!firstAccelDone) {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
            rollAngle = atan2(-gravity[0].toDouble(), gravity[1].toDouble()).toFloat()
            firstAccelDone = true
            return
        }

        // Low-pass filter to extract gravity vector
        gravity[0] = GRAVITY_LP * gravity[0] + (1f - GRAVITY_LP) * event.values[0]
        gravity[1] = GRAVITY_LP * gravity[1] + (1f - GRAVITY_LP) * event.values[1]
        gravity[2] = GRAVITY_LP * gravity[2] + (1f - GRAVITY_LP) * event.values[2]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}