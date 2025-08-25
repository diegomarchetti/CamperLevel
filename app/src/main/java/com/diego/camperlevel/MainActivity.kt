package com.diego.camperlevel

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diego.camperlevel.data.DataSender
import com.diego.camperlevel.data.Prefs
import com.diego.camperlevel.data.SensorHandler
import com.diego.camperlevel.data.TiltData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                LevelScreen()
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Preferenze (offset + orientamento display) ---
    val prefs = remember { Prefs(context) }
    var pitch0 by remember { mutableStateOf(prefs.pitch0) }
    var roll0  by remember { mutableStateOf(prefs.roll0) }
    var faceDown by rememberSaveable { mutableStateOf(prefs.faceDown) } // false=▲ (su), true=▼ (giù)

    // --- Stato sensori (Double) ---
    var pitch by remember { mutableStateOf(0.0) }
    var roll  by remember { mutableStateOf(0.0) }

    // --- Sender Data Layer ---
    val sender = remember { DataSender(context) }
    var sentStatus by remember { mutableStateOf("—") }

    // --- Tone generator per beep ---
    val toneGen = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }
    DisposableEffect(Unit) { onDispose { toneGen.release() } }
    fun beepShort() { @Suppress("DEPRECATION") toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
    fun beepLong()  { @Suppress("DEPRECATION") toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200) }

    // --- Sensor handler: aggiorna Δ e invia sempre ---
    val sensor = remember {
        SensorHandler(context) { pDeg, rDeg ->
            pitch = pDeg
            roll  = rDeg
            val dPitch = (pitch - pitch0)
            val dRoll  = (roll  - roll0)
            sender.sendTilt(TiltData(dPitch, dRoll)) { msg -> sentStatus = msg }
        }
    }
    LaunchedEffect(Unit) { sensor.start() }
    DisposableEffect(Unit) { onDispose { sensor.stop() } }

    val dPitch = (pitch - pitch0)
    val dRoll  = (roll  - roll0)

    var isCalibrating by remember { mutableStateOf(false) }
    var showFlipHint by remember { mutableStateOf(false) } // label “gira il telefono” solo in ▼

    // Helper calibrazione immediata
    fun calibrateNow() {
        pitch0 = pitch
        roll0  = roll
        prefs.pitch0 = pitch0
        prefs.roll0  = roll0
        beepLong()
    }

    Scaffold(
         topBar = { TopAppBar(title = { Text("") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // Canvas livella
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                BubbleLevelCanvas(
                    pitch = dPitch.toFloat(),
                    roll  = dRoll.toFloat(),
                    faceDown = faceDown
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("ΔPitch ↕: ${formatDeg(dPitch)}°", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(16.dp))
                Text("ΔRoll ↔: ${formatDeg(dRoll)}°",  fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Offset (pitch0=${formatDeg(pitch0)}°, roll0=${formatDeg(roll0)}°)",
                color = Color(0x88FFFFFF),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(20.dp))

            // Toggle orientamento (persistente)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(
                    enabled = !isCalibrating,
                    checked = faceDown,
                    onCheckedChange = {
                        faceDown = it
                        prefs.faceDown = it
                    }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Display: " + if (faceDown) "▼" else "▲",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Label di avviso durante la calibrazione in modalità ▼
            if (faceDown && showFlipHint) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Gira il telefono e appoggialo con il display verso il tavolo",
                    color = Color(0xFF673AB7), // giallo ambra
                    fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Calibrazione con countdown se ▼, altrimenti immediata
            Button(
                enabled = !isCalibrating,
                onClick = {
                    if (faceDown) {
                        // Countdown 5s con beep + label di avviso
                        scope.launch {
                            isCalibrating = true
                            showFlipHint = true
                            beepShort() // start
                            repeat(4) {
                                delay(1000)
                                beepShort()
                            }
                            delay(1000)      // raggiungi 5s
                            calibrateNow()   // salva offset + beep lungo
                            showFlipHint = false
                            isCalibrating = false
                        }
                    } else {
                        // Display su: calibrazione immediata + beep lungo
                        calibrateNow()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(if (isCalibrating) "Calibrating..." else "Calibrate")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Sent: $sentStatus",
                color = Color(0x88FFFFFF),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

/**
 * Canvas livella:
 * - cerchio con croce
 * - bolla verde vincolata all’interno del cerchio
 * - inversione segni in modalità Display ▼ per mantenere lo stesso “senso” di movimento
 */
@Composable
private fun BubbleLevelCanvas(pitch: Float, roll: Float, faceDown: Boolean) {
    val bubbleRadiusDp = 20.dp
    val maxAngle = 45f // mappa ±45° al bordo, poi clamp

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        val gridStrokePx = 2.dp.toPx()
        val bubbleRadiusPx = bubbleRadiusDp.toPx()

        val sizeMin = min(size.width, size.height)
        val radius = sizeMin * 0.48f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Cerchio
        drawCircle(
            color = Color(0xFF2A2A2A),
            radius = radius,
            center = center,
            style = Stroke(width = gridStrokePx)
        )
        // Croce
        drawLine(
            color = Color(0xFF2A2A2A),
            start = Offset(center.x - radius, center.y),
            end   = Offset(center.x + radius, center.y),
            strokeWidth = gridStrokePx
        )
        drawLine(
            color = Color(0xFF2A2A2A),
            start = Offset(center.x, center.y - radius),
            end   = Offset(center.x, center.y + radius),
            strokeWidth = gridStrokePx
        )

        // Segni: ▲ = (-1, -1), ▼ = (+1, +1)
        val signX = if (faceDown) +1f else -1f
        val signY = if (faceDown) +1f else -1f

        val maxTravel = radius - bubbleRadiusPx
        val pitchClamped = pitch.coerceIn(-maxAngle, maxAngle)
        val rollClamped  = roll.coerceIn(-maxAngle, maxAngle)

        var dx = signX * (pitchClamped / maxAngle) * maxTravel   // sinistra/destra
        var dy = signY * (rollClamped  / maxAngle) * maxTravel   // su/giù

        val dist = hypot(dx, dy)
        if (dist > maxTravel) {
            val s = maxTravel / dist
            dx *= s
            dy *= s
        }

        val bubbleCenter = center + Offset(dx, dy)

        drawCircle(
            color = Color(0xFF39D353),
            radius = bubbleRadiusPx,
            center = bubbleCenter
        )
    }
}

private fun formatDeg(v: Double): String =
    String.format(java.util.Locale.getDefault(), "%.2f", v)
