package com.alpha.resmon

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import kotlin.math.abs

data class Snapshot(
    val ramUsedMb: Long = 0,
    val ramTotalMb: Long = 0,
    val ramPct: Float = 0f,
    val appCpuPct: Float = 0f,
    val appPssMb: Long = 0,
    val coreMhz: List<Int> = emptyList(),
    val batteryPct: Int = 0,
    val tempC: Float = 0f,
    val currentMa: Int = 0,
    val charging: Boolean = false,
    val rxKBps: Float = 0f,
    val txKBps: Float = 0f,
    val storageUsedGb: Float = 0f,
    val storageTotalGb: Float = 0f,
    val thermal: String = "n/a",
)

/** Reads everything a normal (non-root) app is allowed to see. Call [sample] once per tick. */
class Sampler(private val ctx: Context) {

    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cores = Runtime.getRuntime().availableProcessors()

    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()
    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()

    fun sample(): Snapshot {
        val nowWall = SystemClock.elapsedRealtime()
        val dtMs = (nowWall - lastWallMs).coerceAtLeast(1)

        // RAM (system wide)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = mi.totalMem / MB
        val usedMb = (mi.totalMem - mi.availMem) / MB

        // CPU and memory of this app only (other apps are hidden from us)
        val cpuMs = Process.getElapsedCpuTime()
        val appCpu = ((cpuMs - lastCpuMs).toFloat() / dtMs / cores * 100f).coerceIn(0f, 100f)
        lastCpuMs = cpuMs
        val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024L

        // Network speed, from the total byte counters
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val rxSpeed = if (rx >= 0 && lastRx >= 0) (rx - lastRx) * 1000f / dtMs / 1024f else 0f
        val txSpeed = if (tx >= 0 && lastTx >= 0) (tx - lastTx) * 1000f / dtMs / 1024f else 0f
        lastRx = rx; lastTx = tx
        lastWallMs = nowWall

        // Battery
        val b: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        // Some phones report microamps, some milliamps. Values above 20000 are almost surely microamps.
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = if (abs(raw) > 20_000) raw / 1000 else raw

        // Storage (internal data partition)
        val st = StatFs(Environment.getDataDirectory().path)
        val totalGb = st.totalBytes / GB
        val usedGb = (st.totalBytes - st.availableBytes) / GB

        return Snapshot(
            ramUsedMb = usedMb,
            ramTotalMb = totalMb,
            ramPct = if (totalMb > 0) usedMb * 100f / totalMb else 0f,
            appCpuPct = appCpu,
            appPssMb = pss,
            coreMhz = readCoreFreqs(),
            batteryPct = if (scale > 0) level * 100 / scale else level,
            tempC = temp,
            currentMa = currentMa,
            charging = charging,
            rxKBps = rxSpeed.coerceAtLeast(0f),
            txKBps = txSpeed.coerceAtLeast(0f),
            storageUsedGb = usedGb,
            storageTotalGb = totalGb,
            thermal = thermalLabel(),
        )
    }

    /** Many phones block this file; if so the list is simply empty and the UI hides the card. */
    private fun readCoreFreqs(): List<Int> = (0 until cores).mapNotNull { i ->
        try {
            File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                .readText().trim().toInt() / 1000
        } catch (e: Exception) {
            null
        }
    }

    private fun thermalLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "n/a"
        }
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val GB = 1024f * 1024f * 1024f
    }
}
