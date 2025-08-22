package com.diego.camperlevel

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
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

    // --- Preferenze (pitch0/roll0 come Double) ---
    val prefs = remember { Prefs(context) }
    var pitch0 by remember { mutableStateOf(prefs.pitch0) }
    var roll0  by remember { mutableStateOf(prefs.roll0) }

    // --- Stato sensori (Double) ---
    var pitch by remember { mutableStateOf(0.0) }
    var roll  by remember { mutableStateOf(0.0) }

    // --- Toggle display ▲/▼ (non persistito: se vuoi lo aggiungiamo a Prefs dopo) ---
    var faceDown by rememberSaveable { mutableStateOf(false) } // false=▲, true=▼

    // --- Sender Data Layer ---
    val sender = remember { DataSender(context) }
    var sentStatus by remember { mutableStateOf("—") }

    // --- Sensor handler con callback ---
    val sensor = remember {
        SensorHandler(context) { pDeg, rDeg ->
            pitch = pDeg
            roll  = rDeg

            // Calcola Δ e invia sempre
            val dPitch = (pitch - pitch0)
            val dRoll  = (roll  - roll0)
            sender.sendTilt(
                TiltData(dPitch, dRoll)
            ) { msg -> sentStatus = msg }
        }
    }

    // Avvio/stop sensori legati al lifecycle del composable
    LaunchedEffect(Unit) { sensor.start() }
    DisposableEffect(Unit) { onDispose { sensor.stop() } }

    val dPitch = (pitch - pitch0)
    val dRoll  = (roll  - roll0)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Camper Level") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            // Spazio extra sotto la AppBar per “staccare” il cerchio
            Spacer(Modifier.height(12.dp))

            // Canvas livella centrato (quadrato)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                BubbleLevelCanvas(
                    pitch = dPitch.toFloat(),
                    roll  = dRoll.toFloat()
                )
            }

            Spacer(Modifier.height(16.dp))

            // Letture Δ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("ΔPitch: ${formatDeg(dPitch)}°", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(16.dp))
                Text("ΔRoll: ${formatDeg(dRoll)}°",  fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Offset (pitch0=${formatDeg(pitch0)}°, roll0=${formatDeg(roll0)}°)",
                color = Color(0x88FFFFFF),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(Modifier.height(20.dp))

            // Toggle modalità display (stessa riga, etichetta compatta)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(
                    checked = faceDown,
                    onCheckedChange = { faceDown = it }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Display: " + if (faceDown) "▼" else "▲",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(16.dp))

            // Calibrazione immediata (salva offset correnti)
            Button(
                onClick = {
                    // Nota: quando introdurremo il countdown+beep lo attiveremo solo se faceDown==true
                    pitch0 = pitch
                    roll0  = roll
                    prefs.pitch0 = pitch0
                    prefs.roll0  = roll0
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Calibrate (immediata)")
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
 */
@Composable
private fun BubbleLevelCanvas(pitch: Float, roll: Float) {
    val bubbleRadiusDp = 12.dp
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

        // Spostamento bolla (clamp e vincolo nel cerchio)
        val maxTravel = radius - bubbleRadiusPx
        var dx = (roll.coerceIn(-maxAngle, maxAngle) / maxAngle) * maxTravel
        var dy = -(pitch.coerceIn(-maxAngle, maxAngle) / maxAngle) * maxTravel // Y verso il basso → inverti

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
