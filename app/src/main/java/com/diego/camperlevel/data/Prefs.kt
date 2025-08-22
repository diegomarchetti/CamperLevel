package com.diego.camperlevel.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("camperlevel_prefs", Context.MODE_PRIVATE)

    var pitch0: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("pitch0", 0L))
        set(value) = sp.edit().putLong("pitch0", java.lang.Double.doubleToRawLongBits(value)).apply()

    var roll0: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong("roll0", 0L))
        set(value) = sp.edit().putLong("roll0", java.lang.Double.doubleToRawLongBits(value)).apply()
}
