package com.diego.camperlevel.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

// Unica definizione di TiltData (assicurati di NON averla in altri file)
data class TiltData(
    val deltaPitch: Double,
    val deltaRoll: Double
)

class DataSender(private val context: Context) {

    /**
     * Invia i dati su /level/tilt.
     * Aggiungo "nonce" per forzare TYPE_CHANGED a ogni invio.
     * Risultato restituito via callback (niente coroutines).
     */
    fun sendTilt(
        data: TiltData,
        onResult: (String) -> Unit = {}
    ) {
        val req = PutDataMapRequest.create("/level/tilt").apply {
            dataMap.putDouble("deltaPitch", data.deltaPitch)
            dataMap.putDouble("deltaRoll",  data.deltaRoll)
            dataMap.putLong("nonce", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        val client = Wearable.getDataClient(context)
        client.putDataItem(req)
            .addOnSuccessListener { item ->
                val uri = item.uri.toString()
                Log.d("CamperLevel/Phone", "DataItem inviato: $uri")
                onResult("Sent: ok $uri")
            }
            .addOnFailureListener { e ->
                val msg = e.localizedMessage ?: "putDataItem failed"
                Log.e("CamperLevel/Phone", "Errore invio: $msg", e)
                onResult("Sent: ERR $msg")
            }
    }
}
