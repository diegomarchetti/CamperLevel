package com.diego.camperlevel

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diego.camperlevel.data.DataSender
import com.diego.camperlevel.data.TiltData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.Math.toDegrees
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.min

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // filtro passa-basso
    private val alpha = 0.15f
    private var ax = 0f; private var ay = 0f; private var az = 0f

    // gradi correnti
    private var pitchDeg = 0.0
    private var rollDeg  = 0.0

    // UI state (mostrato a schermo)
    private var uiPitch by mutableStateOf(0.0)
    private var uiRoll  by mutableStateOf(0.0)

    // invio in tempo reale
    private lateinit var sender: DataSender
    private var sendRealtimeEnabled = false
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var sendJob: Job? = null
    private var lastSentAtMs = 0L
    private var lastSentPitch = 0.0
    private var lastSentRoll  = 0.0
    private val minIntervalMs = 100L
    private val minDeltaDeg   = 0.02

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 🔒 schermo sempre acceso
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sender = DataSender(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF101010) // sfondo scuro
                ) {
                    UI()
                }
            }
        }
    }

    @Composable
    private fun UI() {
        var uiSending by remember { mutableStateOf(false) }
        var lastSendUri by remember { mutableStateOf<String?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Camper Level",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            // Canvas quadrato con bolla
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f) // quadrato
                    .background(Color(0xFF101010)),
                contentAlignment = Alignment.Center
            ) {
                LevelCanvas(
                    pitch = uiPitch,
                    roll = uiRoll,
                    bubbleRadiusPx = 14.dp
                )
            }

            Text(
                "ΔPitch: ${"%.2f".format(uiPitch)}°   ΔRoll: ${"%.2f".format(uiRoll)}°",
                color = Color(0xFFEEEEEE)
            )

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
                Text("  Invia Δ in tempo reale al Watch", color = Color.White)
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

            Text("Sent: ${lastSendUri ?: "-"}", color = Color(0xFFAAAAAA))
        }
    }

    @Composable
    private fun LevelCanvas(
        pitch: Double,
        roll: Double,
        bubbleRadiusPx: Dp
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = min(w, h) * 0.45f
            val stroke = 3f

            // cerchio esterno
            drawCircle(
                color = Color(0xFF2A2A2A),
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = stroke)
            )

            // crociera
            drawLine(
                color = Color(0xFF333333),
                start = Offset(cx - radius, cy),
                end   = Offset(cx + radius, cy),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF333333),
                start = Offset(cx, cy - radius),
                end   = Offset(cx, cy + radius),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )

            // mappatura gradi -> posizione
            // ipotesi: 15° piena escursione fino al bordo
            val maxDeg = 15.0
            val k = (radius - bubbleRadiusPx.toPx() - 6f).toFloat() // margine
            val x = (roll / maxDeg).toFloat().coerceIn(-1f, 1f) * k
            val y = (pitch / maxDeg).toFloat().coerceIn(-1f, 1f) * k

            // bolla
            drawCircle(
                color = Color(0xFF00E676),
                radius = bubbleRadiusPx.toPx(),
                center = Offset(cx + x, cy - y) // pitch positivo = bolla su
            )
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

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // passa-basso
            ax = ax + alpha * (event.values[0] - ax)
            ay = ay + alpha * (event.values[1] - ay)
            az = az + alpha * (event.values[2] - az)

            // normalizza
            val g = sqrt(ax * ax + ay * ay + az * az)
            val nx = ax / g; val ny = ay / g; val nz = az / g

            // ✅ calcolo gradi per portrait:
            // - pitch: avanti/indietro (usa NY)
            // - roll : sinistra/destra (usa NX)
            val pitch = toDegrees(atan2(ny.toDouble(), sqrt(nx.toDouble().pow(2) + nz.toDouble().pow(2))))
            val roll  = toDegrees(atan2(-nx.toDouble(), nz.toDouble()))

            pitchDeg = pitch
            rollDeg  = roll

            // aggiorna testi/bolla
            uiPitch = pitchDeg
            uiRoll  = rollDeg

            // invio realtime pilotato dal sensore
            maybeSendRealtime()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun maybeSendRealtime() {
        if (!sendRealtimeEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastSentAtMs < minIntervalMs) return

        val dPitch = abs(pitchDeg - lastSentPitch)
        val dRoll  = abs(rollDeg  - lastSentRoll)
        if (dPitch < minDeltaDeg && dRoll < minDeltaDeg) return

        lastSentAtMs = now
        lastSentPitch = pitchDeg
        lastSentRoll  = rollDeg

        sendJob?.cancel()
        sendJob = ioScope.launch {
            try {
                val uri = sender.sendTilt(TiltData(pitchDeg, rollDeg))
                Log.d("CamperLevel/Phone", "RT Δ=(${String.format("%.2f", pitchDeg)}, ${String.format("%.2f", rollDeg)}) -> $uri")
            } catch (e: Exception) {
                Log.e("CamperLevel/Phone", "RT send FAIL: ${e.message}", e)
            }
        }
    }
}
