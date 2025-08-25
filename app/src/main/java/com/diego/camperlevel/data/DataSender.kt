package com.diego.camperlevel.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable

data class TiltData(val deltaPitch: Double, val deltaRoll: Double)

class DataSender(private val context: Context) {

    private val nodeClient = Wearable.getNodeClient(context)
    private val messageClient = Wearable.getMessageClient(context)

    private val PATH_MSG = "/level/tilt_msg"
    private val TAG = "CamperLevel/PhoneMsg"

    // Throttling: max 20 Hz
    private val minIntervalMs = 50L
    private var lastSentNs = 0L

    // (opzionale) piccola soglia per evitare spam quando quasi fermi
    private val epsilonDeg = 0.01  // manda sempre se vuoi: metti 0.0
    private var lastPitch = Double.NaN
    private var lastRoll  = Double.NaN

    fun sendTilt(data: TiltData, onResult: (String) -> Unit = {}) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if ((nowNs - lastSentNs) < minIntervalMs * 1_000_000L) return

        // Se il cambiamento è minuscolo, salta (toglimi se vuoi 1:1 assoluto)
        if (!lastPitch.isNaN() && !lastRoll.isNaN()) {
            if (kotlin.math.abs(data.deltaPitch - lastPitch) < epsilonDeg &&
                kotlin.math.abs(data.deltaRoll  - lastRoll)  < epsilonDeg) {
                lastSentNs = nowNs
                return
            }
        }
        lastPitch = data.deltaPitch
        lastRoll  = data.deltaRoll
        lastSentNs = nowNs

        val payload = "${data.deltaPitch};${data.deltaRoll}".toByteArray()

        nodeClient.connectedNodes
            .addOnSuccessListener { nodes: List<Node> ->
                if (nodes.isEmpty()) {
                    onResult("Msg: no nodes")
                    return@addOnSuccessListener
                }
                for (n in nodes) {
                    messageClient.sendMessage(n.id, PATH_MSG, payload)
                        .addOnSuccessListener {
                            // Log leggero; commenta se troppo verboso
                            Log.d(TAG, "Msg OK -> ${n.displayName} (${n.id.take(4)}…)  [${data.deltaPitch}, ${data.deltaRoll}]")
                        }
                        .addOnFailureListener { ex ->
                            Log.e(TAG, "Msg FAIL -> ${n.displayName}: ${ex.message}", ex)
                        }
                }
                onResult("Msg sent to ${nodes.size}")
            }
            .addOnFailureListener { ex ->
                Log.e(TAG, "Nodes FAIL: ${ex.message}", ex)
                onResult("Msg nodes err: ${ex.message}")
            }
    }
}
