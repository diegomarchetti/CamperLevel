package com.diego.camperlevel.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Gestisce i sensori del telefono (accelerometro).
 * Calcola pitch/roll in gradi e applica filtro passa-basso.
 */
class SensorHandler(
    context: Context,
    private val onTiltChanged: (pitch: Double, roll: Double) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // valori filtrati
    private var filteredX = 0f
    private var filteredY = 0f
    private var filteredZ = 0f

    // coefficiente filtro passa-basso (0.1 = molto lento, 0.5 = più reattivo)
    private val alpha = 0.1f

    fun start() {
        accelerometer?.also {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // filtro passa-basso
        filteredX = alpha * x + (1 - alpha) * filteredX
        filteredY = alpha * y + (1 - alpha) * filteredY
        filteredZ = alpha * z + (1 - alpha) * filteredZ

        // calcolo pitch/roll in gradi
        val norm = sqrt(filteredX * filteredX + filteredY * filteredY + filteredZ * filteredZ)
        val nx = filteredX / norm
        val ny = filteredY / norm
        val nz = filteredZ / norm

        val pitch = Math.toDegrees(atan2(-nx.toDouble(), sqrt(ny * ny + nz * nz).toDouble()))
        val roll = Math.toDegrees(atan2(ny.toDouble(), nz.toDouble()))

        onTiltChanged(pitch, roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // non usato
    }
}
