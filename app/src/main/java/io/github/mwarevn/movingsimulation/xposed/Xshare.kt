package io.github.mwarevn.movingsimulation.xposed

import de.robv.android.xposed.XSharedPreferences
import io.github.mwarevn.movingsimulation.BuildConfig

class Xshare {
    private val prefs: XSharedPreferences = XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_preferences")

    init {
        prefs.makeWorldReadable()
    }

    private fun reload() {
        prefs.reload()
    }

    val isStarted: Boolean get() { reload(); return prefs.getBoolean("isStarted", false) }
    val getLat: Double get() { reload(); return prefs.getString("latitude", "45.0")?.toDouble() ?: 45.0 }
    val getLng: Double get() { reload(); return prefs.getString("longitude", "0.0")?.toDouble() ?: 0.0 }
    val isRandomPosition: Boolean get() { reload(); return prefs.getBoolean("isRandomPosition", false) }
    val accuracy: String? get() { reload(); return prefs.getString("accuracy", "15") }
    val getBearing: Float get() { reload(); return prefs.getFloat("bearing", 0f) }
    val getSpeed: Float get() { reload(); return prefs.getFloat("speed", 0f) }
    val isHookedSystem: Boolean get() { reload(); return prefs.getBoolean("isSystemHooked", false) }

    // 補齊導航同步相關屬性 (用於 SensorSpoofHook)
    val getSyncedBearing: Float get() { reload(); return prefs.getFloat("synced_bearing", 0f) }
    val getSyncedActualSpeed: Float get() { reload(); return prefs.getFloat("synced_actual_speed", 0f) }
    val getSyncedCurveReduction: Float get() { reload(); return prefs.getFloat("synced_curve_reduction", 1f) }
}
