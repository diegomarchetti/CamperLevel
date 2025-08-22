package com.diego.camperlevel

import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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
import com.diego.camperlevel.data.Prefs
import com.diego.camperlevel.data.TiltData
import kotlinx.coroutines.*
import java.lang.Math.toDegrees
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // filtro passa-basso
    private val alpha = 0.15f
    private var ax = 0f; private var ay = 0f; private var az = 0f

    // gradi assoluti (non calibrati)
    private var pitchDeg = 0.0
    private var rollDeg  = 0.0

    // offset persistenti
    private lateinit var prefs: Prefs
    private var pitch0 by mutableStateOf(0.0)
    private var roll0  by mutableStateOf(0.0)

    // delta mostrati e inviati
    private var dPitch by mutableStateOf(0.0)
    private var dRoll  by mutableStateOf(0.0)

    // invio in tempo reale
    private lateinit var sender: DataSender
    private var sendRealtimeEnabled = false
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var sendJob: Job? = null
    private var lastSentAtMs = 0L
    private var lastSentPitch = 0.0
    private var lastSentRoll  = 0.0
    private val minIntervalMs = 100L
    private val minDeltaDeg   = 0.02

    // calibrazione (UI state)
    private var faceDownMode by mutableStateOf(false) // false = display su; true = display giù
    private var isCalibrating by mutableStateOf(false)
    private var countdown by mutableStateOf(0)

    // beeper
    private val beeper by lazy { ToneGenerator(AudioManager.STREAM_ALARM, 80) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // schermo sempre acceso
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        prefs = Prefs(this)
        pitch0 = prefs.pitch0
        roll0  = prefs.roll0

        sender = DataSender(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF101010)
                ) { UI() }
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
            Text("Camper Level", color = Color.White, fontWeight = FontWeight.SemiBold)

            // Canvas bolla
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(Color(0xFF101010)),
                contentAlignment = Alignment.Center
            ) {
                LevelCanvas(
                    pitch = dPitch,
                    roll = dRoll,
                    bubbleRadiusPx = 14.dp
                )
            }

            // valori Δ e offset correnti
            Text(
                "ΔPitch: ${"%.2f".format(dPitch)}°   ΔRoll: ${"%.2f".format(dRoll)}°",
                color = Color(0xFFEEEEEE)
            )
            Text(
                "Offset (pitch0=${"%.2f".format(pitch0)}°, roll0=${"%.2f".format(roll0)}°)",
                color = Color(0xFF888888)
            )

            // Toggle invio realtime
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiSending,
                    onCheckedChange = { checked ->
                        uiSending = checked
                        sendRealtimeEnabled = checked
                        Log.d("CamperLevel/Phone", if (checked) "Realtime ON" else "Realtime OFF")
                    }
                )
                Text("  Invia Δ in tempo reale al Watch", color = Color.White)
            }

            // Modalità calibrazione (display su/giù)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = faceDownMode,
                    onCheckedChange = { checked ->
                        faceDownMode = checked
                    }
                )
                val modeText = if (faceDownMode) "Modalità: DISPLAY VERSO IL TAVOLO" else "Modalità: DISPLAY VERSO L’ALTO"
                Text("  $modeText", color = Color.White)
            }

            // Pulsante Calibra (immediata se display su, countdown se display giù)
            Button(
                enabled = !isCalibrating,
                onClick = {
                    if (faceDownMode) {
                        startCountdownAndCalibrate()
                    } else {
                        // display verso l’alto → subito
                        saveOffsets(pitchDeg, rollDeg)
                        beepShort()
                    }
                }
            ) {
                Text(if (faceDownMode) "Calibrate (5s countdown)" else "Calibrate (immediata)")
            }

            // countdown visivo
            if (isCalibrating) {
                Text("Calibrazione tra: $countdown", color = Color(0xFFFFEE58))
            }

            // invio TEST manuale (invia Δ, non i grezzi)
            Button(onClick = {
                ioScope.launch {
                    try {
                        val uri = sender.sendTilt(TiltData(dPitch, dRoll))
                        Log.d("CamperLevel/Phone", "Test send OK: $uri")
                        withContext(Dispatchers.Main) { lastSendUri = "ok $uri" }
                    } catch (e: Exception) {
                        Log.e("CamperLevel/Phone", "Test send FAIL: ${e.message}", e)
                        withContext(Dispatchers.Main) { lastSendUri = "ERR ${e.message}" }
                    }
                }
            }) { Text("Invia TEST (Δ)") }

            Text("Sent: ${lastSendUri ?: "-"}", color = Color(0xFFAAAAAA))
        }
    }

    private fun saveOffsets(pitch: Double, roll: Double) {
        pitch0 = pitch
        roll0  = roll
        prefs.pitch0 = pitch0
        prefs.roll0  = roll0
        Log.d("CamperLevel/Phone", "Offset salvati: pitch0=$pitch0 roll0=$roll0")
    }

    private fun startCountdownAndCalibrate() {
        if (isCalibrating) return
        isCalibrating = true
        countdown = 5
        beepLong() // beep inizio
        CoroutineScope(Dispatchers.Main).launch {
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            // fine countdown → salva offset (display giù)
            saveOffsets(pitchDeg, rollDeg)
            beepDouble() // beep conferma
            isCalibrating = false
        }
    }

    // Beep helper
    private fun beepShort() = beeper.startTone(ToneGenerator.TONE_PROP_ACK, 120)
    private fun beepLong()  = beeper.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
    private fun beepDouble() {
        beeper.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        CoroutineScope(Dispatchers.Main).launch {
            delay(180)
            beeper.startTone(ToneGenerator.TONE_PROP_ACK, 120)
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

            // cerchio
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

            // mapping: ±15° → bordo
            val maxDeg = 15.0
            val k = (radius - bubbleRadiusPx.toPx() - 6f).toFloat()
            val x = (roll / maxDeg).toFloat().coerceIn(-1f, 1f) * k
            val y = (pitch / maxDeg).toFloat().coerceIn(-1f, 1f) * k

            drawCircle(
                color = Color(0xFF00E676),
                radius = bubbleRadiusPx.toPx(),
                center = Offset(cx + x, cy - y)
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

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // passa-basso
            ax = ax + alpha * (event.values[0] - ax)
            ay = ay + alpha * (event.values[1] - ay)
            az = az + alpha * (event.values[2] - az)

            // normalizza
            val g = sqrt(ax * ax + ay * ay + az * az)
            val nx = ax / g; val ny = ay / g; val nz = az / g

            // calcolo gradi per portrait
            val pitch = toDegrees(atan2(ny.toDouble(), sqrt(nx.toDouble().pow(2) + nz.toDouble().pow(2))))
            val roll  = toDegrees(atan2(-nx.toDouble(), nz.toDouble()))

            pitchDeg = pitch
            rollDeg  = roll

            // aggiorna delta
            dPitch = pitchDeg - pitch0
            dRoll  = rollDeg  - roll0

            // invio realtime (Δ)
            maybeSendRealtime()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun maybeSendRealtime() {
        if (!sendRealtimeEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastSentAtMs < minIntervalMs) return

        val dP = abs(dPitch - lastSentPitch)
        val dR = abs(dRoll  - lastSentRoll)
        if (dP < minDeltaDeg && dR < minDeltaDeg) return

        lastSentAtMs = now
        lastSentPitch = dPitch
        lastSentRoll  = dRoll

        sendJob?.cancel()
        sendJob = ioScope.launch {
            try {
                val uri = sender.sendTilt(TiltData(dPitch, dRoll))
                Log.d("CamperLevel/Phone", "RT Δ=(${String.format("%.2f", dPitch)}, ${String.format("%.2f", dRoll)}) -> $uri")
            } catch (e: Exception) {
                Log.e("CamperLevel/Phone", "RT send FAIL: ${e.message}", e)
            }
        }
    }
}
