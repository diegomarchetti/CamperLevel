package com.diego.camperlevel

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.diego.camperlevel.data.DataSender
import com.diego.camperlevel.data.TiltData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.toDegrees

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // stato grezzo e filtrato
    private val alpha = 0.15f // low-pass
    private var ax = 0f; private var ay = 0f; private var az = 0f

    // pitch/roll calcolati
    private var pitchDeg = 0.0
    private var rollDeg  = 0.0

    // invio in tempo reale
    private lateinit var sender: DataSender
    private var sendRealtimeEnabled = false
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var sendJob: Job? = null

    // anti-spam
    private var lastSentAtMs = 0L
    private var lastSentPitch = 0.0
    private var lastSentRoll  = 0.0
    private val minIntervalMs = 100L        // max ~10 Hz
    private val minDeltaDeg   = 0.02        // ignora micro-variazioni

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sender = DataSender(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UI()
                }
            }
        }
    }

    @Composable
    private fun UI() {
        var uiPitch by remember { mutableStateOf(0.0) }
        var uiRoll  by remember { mutableStateOf(0.0) }
        var uiSending by remember { mutableStateOf(false) }
        var lastSendUri by remember { mutableStateOf<String?>(null) }

        // esponi callback per aggiornare la UI dai sensori
        LaunchedEffect(Unit) {
            // nessuna azione qui; UI aggiornata in onSensorChanged
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Camper Level — Phone (STEP 5.3)")
            Text("Pitch: ${"%.2f".format(uiPitch)}°    Roll: ${"%.2f".format(uiRoll)}°")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiSending,
                    onCheckedChange = { checked ->
                        uiSending = checked
                        sendRealtimeEnabled = checked
                        if (checked) {
                            Log.d("CamperLevel/Phone", "Realtime ON")
                        } else {
                            Log.d("CamperLevel/Phone", "Realtime OFF")
                        }
                    }
                )
                Text("  Invia Δ in tempo reale al Watch")
            }

            Button(onClick = {
                // invio test manuale
                ioScope.launch {
                    try {
                        val uri = sender.sendTilt(TiltData(pitchDeg, rollDeg))
                        Log.d("CamperLevel/Phone", "Test send OK: $uri")
                        uiScope.launch { lastSendUri = "ok $uri" }
                    } catch (e: Exception) {
                        Log.e("CamperLevel/Phone", "Test send FAIL: ${e.message}", e)
                        uiScope.launch { lastSendUri = "ERR ${e.message}" }
                    }
                }
            }) {
                Text("Invia TEST")
            }

            Text("Sent: ${lastSendUri ?: "-"}")

            // osserva pitch/roll e aggiorna UI
            LaunchedEffect(pitchDeg, rollDeg) {
                uiPitch = pitchDeg
                uiRoll = rollDeg
                maybeSendRealtime()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // low-pass
            ax = ax + alpha * (event.values[0] - ax)
            ay = ay + alpha * (event.values[1] - ay)
            az = az + alpha * (event.values[2] - az)

            // calcolo pitch/roll in gradi (convenzionale)
            val g = sqrt(ax * ax + ay * ay + az * az)
            val nx = ax / g; val ny = ay / g; val nz = az / g

            // pitch: rotazione intorno all’asse X (telefono avanti/indietro)
            val pitch = toDegrees(atan2(-nx.toDouble(), sqrt(ny.toDouble().pow(2) + nz.toDouble().pow(2))))
            // roll : rotazione intorno all’asse Y (telefono sinistra/destra)
            val roll  = toDegrees(atan2(ny.toDouble(), nz.toDouble()))

            pitchDeg = pitch
            rollDeg  = roll
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun maybeSendRealtime() {
        if (!sendRealtimeEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastSentAtMs < minIntervalMs) return

        val dPitch = kotlin.math.abs(pitchDeg - lastSentPitch)
        val dRoll  = kotlin.math.abs(rollDeg  - lastSentRoll)
        if (dPitch < minDeltaDeg && dRoll < minDeltaDeg) return

        lastSentAtMs = now
        lastSentPitch = pitchDeg
        lastSentRoll  = rollDeg

        sendJob?.cancel()
        sendJob = ioScope.launch {
            try {
                val uri = sender.sendTilt(TiltData(pitchDeg, rollDeg))
                Log.d("CamperLevel/Phone", "RT send Δ=(${String.format("%.2f", pitchDeg)}, ${String.format("%.2f", rollDeg)}) -> $uri")
            } catch (e: Exception) {
                Log.e("CamperLevel/Phone", "RT send FAIL: ${e.message}", e)
            }
        }
    }
}
