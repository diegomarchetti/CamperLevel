package com.diego.camperlevel.wear.presentation

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.*
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class MainActivity : ComponentActivity(),
    DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener {

    private var dPitch by mutableStateOf(0.0)
    private var dRoll  by mutableStateOf(0.0)
    private var lastInRange = false

    private val PATH_DATA = "/level/tilt"
    private val PATH_MSG  = "/level/tilt_msg"
    private val tag  = "WearMain"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
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
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val dc = Wearable.getDataClient(this)
        val mc = Wearable.getMessageClient(this)

        // Listener “aperti”: filtriamo per path noi
        dc.addListener(this)
        mc.addListener(this)
        Log.d(tag, "addListener() Data+Message registrati")

        // Snapshot iniziale: prendi tutti i DataItem e usa solo quelli su /level/tilt
        dc.dataItems
            .addOnSuccessListener { items ->
                var latestPitch: Double? = null
                var latestRoll: Double? = null
                items.use {
                    for (item in it) {
                        if (item.uri.path == PATH_DATA) {
                            val map = DataMapItem.fromDataItem(item).dataMap
                            val p = map.readAsDouble("deltaPitch", "dPitch", "pitch")
                            val r = map.readAsDouble("deltaRoll",  "dRoll",  "roll")
                            if (p != null && r != null) {
                                latestPitch = p
                                latestRoll  = r
                            }
                        }
                    }
                }
                if (latestPitch != null && latestRoll != null) {
                    Log.d(tag, "Snapshot p=$latestPitch r=$latestRoll")
                    applyTilt(latestPitch!!, latestRoll!!)
                } else {
                    Log.d(tag, "Snapshot: nessun dato su $PATH_DATA")
                }
            }
            .addOnFailureListener { e ->
                Log.e(tag, "Snapshot error: ${e.message}", e)
            }
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        Log.d(tag, "removeListener() Data+Message")
    }

    // DataClient
    override fun onDataChanged(events: DataEventBuffer) {
        events.use {
            for (event in it) {
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == PATH_DATA) {
                    val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val p = map.readAsDouble("deltaPitch", "dPitch", "pitch")
                    val r = map.readAsDouble("deltaRoll",  "dRoll",  "roll")
                    if (p != null && r != null) {
                        Log.d(tag, "Update(DataItem) p=$p r=$r")
                        applyTilt(p, r)
                    } else {
                        Log.w(tag, "Update(DataItem) con chiavi/tipi non validi")
                    }
                }
            }
        }
    }

    // MessageClient (fallback)
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == PATH_MSG) {
            val txt = String(event.data)
            val parts = txt.split(';')
            val p = parts.getOrNull(0)?.toDoubleOrNull()
            val r = parts.getOrNull(1)?.toDoubleOrNull()
            if (p != null && r != null) {
                Log.d(tag, "Update(Message) p=$p r=$r")
                applyTilt(p, r)
            } else {
                Log.w(tag, "Update(Message) parse fallita: '$txt'")
            }
        }
    }

    private fun applyTilt(p: Double, r: Double) {
        dPitch = p
        dRoll  = r
        val inRangeNow = (abs(p) <= 0.7) && (abs(r) <= 0.7)
        if (inRangeNow && !lastInRange) vibrateOnce()
        lastInRange = inRangeNow
    }

    private fun vibrateOnce() {
        val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            vib.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(200)
        }
    }
}

private fun DataMap.readAsDouble(vararg keys: String): Double? {
    for (k in keys) {
        if (!containsKey(k)) continue
        try { return getDouble(k) } catch (_: Throwable) {}
        try { return getFloat(k).toDouble() } catch (_: Throwable) {}
        try { return getInt(k).toDouble() } catch (_: Throwable) {}
    }
    return null
}

/** Canvas livella (tema scuro) */
@androidx.compose.runtime.Composable
private fun BubbleLevelCanvas(pitch: Float, roll: Float) {
    val bubbleRadiusDp = 15.dp
    val maxAngle = 45f

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
    ) {
        val gridStrokePx = 2.dp.toPx()
        val bubbleRadiusPx = bubbleRadiusDp.toPx()

        val sizeMin = min(size.width, size.height)
        val radius = sizeMin * 0.48f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(Color(0xFF888888), radius, center, style = Stroke(width = gridStrokePx))
        drawLine(Color(0xFF888888), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), gridStrokePx)
        drawLine(Color(0xFF888888), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), gridStrokePx)

        val maxTravel = radius - bubbleRadiusPx
        val pitchClamped = pitch.coerceIn(-maxAngle, maxAngle)
        val rollClamped  = roll.coerceIn(-maxAngle, maxAngle)

        var dx = -(pitchClamped / maxAngle) * maxTravel
        var dy = -(rollClamped  / maxAngle) * maxTravel

        val dist = hypot(dx, dy)
        if (dist > maxTravel) {
            val s = maxTravel / dist
            dx *= s; dy *= s
        }
        drawCircle(Color(0xFF39D353), bubbleRadiusPx, center + Offset(dx, dy))
    }
}
