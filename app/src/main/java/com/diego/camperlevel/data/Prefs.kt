package com.diego.camperlevel.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Gestione persistente di:
 * - offset di calibrazione (pitch0/roll0, in gradi)
 * - modalità di calibrazione (faceDown: true=display verso tavolo, false=verso l’alto)
 */
class Prefs private constructor(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.getSharedPreferences("camper_level_prefs", Context.MODE_PRIVATE)

    var pitch0: Float
        get() = sp.getFloat(KEY_PITCH0, 0f)
        set(value) = sp.edit { putFloat(KEY_PITCH0, value) }

    var roll0: Float
        get() = sp.getFloat(KEY_ROLL0, 0f)
        set(value) = sp.edit { putFloat(KEY_ROLL0, value) }

    var faceDown: Boolean
        get() = sp.getBoolean(KEY_FACE_DOWN, false)
        set(value) = sp.edit { putBoolean(KEY_FACE_DOWN, value) }

    fun saveOffsets(pitchDeg: Float, rollDeg: Float) {
        sp.edit {
            putFloat(KEY_PITCH0, pitchDeg)
            putFloat(KEY_ROLL0, rollDeg)
        }
    }

    companion object {
        private const val KEY_PITCH0 = "pitch0_deg"
        private const val KEY_ROLL0  = "roll0_deg"
        private const val KEY_FACE_DOWN = "face_down_mode"

        @Volatile private var INSTANCE: Prefs? = null

        fun get(ctx: Context): Prefs =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Prefs(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}
