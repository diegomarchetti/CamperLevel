package com.diego.camperlevel.wear.presentation

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

/**
 * Listener **senza filtro URI** (più affidabile su alcuni dispositivi),
 * e snapshot allo start per leggere l'ultimo DataItem.
 */
class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {

    private lateinit var dataClient: DataClient

    private var lastPitch: Double = 0.0
    private var lastRoll: Double = 0.0
    private var onDataUpdated: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataClient = Wearable.getDataClient(this)

        setContent {
            MaterialTheme {
                var pitch by remember { mutableStateOf(lastPitch) }
                var roll by remember { mutableStateOf(lastRoll) }

                fun refresh() {
                    pitch = lastPitch
                    roll = lastRoll
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                ) {
                    Text("Camper Level — Wear (Δ live)")
                    Text("Δpitch: ${"%.2f".format(pitch)}°")
                    Text("Δroll : ${"%.2f".format(roll)}°")
                    Text("(tieni aperta anche l’app Phone)")
                }

                onDataUpdated = { refresh() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 1) Listener globale (nessun filtro URI)
        dataClient.addListener(this)
        Log.d("CamperLevel/Wear", "Listener registrato (globale)")

        // 2) Snapshot: leggi l’ultimo DataItem e aggiorna subito la UI
        dataClient.dataItems
            .addOnSuccessListener { buffer ->
                var found = false
                try {
                    for (item in buffer) { // <-- itera correttamente il DataItemBuffer
                        if (item.uri.path == "/level/tilt") {
                            val dm = DataMapItem.fromDataItem(item).dataMap
                            lastPitch = dm.getDouble("deltaPitch", 0.0)
                            lastRoll = dm.getDouble("deltaRoll", 0.0)
                            Log.d("CamperLevel/Wear", "Snapshot Δpitch=$lastPitch Δroll=$lastRoll")
                            found = true
                            onDataUpdated?.invoke()
                        }
                    }
                } finally {
                    buffer.release() // rilascia sempre il buffer
                }
                if (!found) {
                    Log.d("CamperLevel/Wear", "Snapshot: nessun DataItem su /level/tilt")
                }
            }
            .addOnFailureListener { e ->
                Log.e("CamperLevel/Wear", "Snapshot fallito: ${e.message}", e)
            }
    }

    override fun onPause() {
        super.onPause()
        dataClient.removeListener(this)
        Log.d("CamperLevel/Wear", "Listener rimosso")
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            for (event in buffer) {
                if (event.type == DataEvent.TYPE_CHANGED) {
                    val item = event.dataItem
                    if (item.uri.path == "/level/tilt") {
                        val dm = DataMapItem.fromDataItem(item).dataMap
                        lastPitch = dm.getDouble("deltaPitch", 0.0)
                        lastRoll = dm.getDouble("deltaRoll", 0.0)
                        Log.d(
                            "CamperLevel/Wear",
                            "onDataChanged Δpitch=$lastPitch Δroll=$lastRoll (uri=${item.uri})"
                        )
                        onDataUpdated?.invoke()
                    }
                }
            }
        }
    }
}
