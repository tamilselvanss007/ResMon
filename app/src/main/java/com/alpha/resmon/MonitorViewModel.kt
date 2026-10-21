package com.alpha.resmon

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

const val HISTORY = 60 // seconds of graph history

data class AppUsage(val name: String, val minutes: Long)

data class UiState(
    val now: Snapshot = Snapshot(),
    val ramHist: List<Float> = emptyList(),
    val cpuHist: List<Float> = emptyList(),
    val rxHist: List<Float> = emptyList(),
    val txHist: List<Float> = emptyList(),
    val tempHist: List<Float> = emptyList(),
    val topApps: List<AppUsage> = emptyList(),
    val hasUsageAccess: Boolean = false,
)

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val sampler = Sampler(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var job: kotlinx.coroutines.Job? = null

    /** Called from the UI when it becomes visible, so we do not burn battery in the background. */
    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.Default) {
            var tick = 0
            while (isActive) {
                val s = sampler.sample()
                val old = _state.value
                var apps = old.topApps
                val access = hasUsageAccess()
                if (access && tick % 5 == 0) apps = loadTopApps()
                _state.value = UiState(
                    now = s,
                    ramHist = (old.ramHist + s.ramPct).takeLast(HISTORY),
                    cpuHist = (old.cpuHist + s.appCpuPct).takeLast(HISTORY),
                    rxHist = (old.rxHist + s.rxKBps).takeLast(HISTORY),
                    txHist = (old.txHist + s.txKBps).takeLast(HISTORY),
                    tempHist = (old.tempHist + s.tempC).takeLast(HISTORY),
                    topApps = if (access) apps else emptyList(),
                    hasUsageAccess = access,
                )
                tick++
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val ctx = getApplication<Application>()
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun loadTopApps(): List<AppUsage> {
        val ctx = getApplication<Application>()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val pmgr = ctx.packageManager
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
            .filter { it.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(8)
            .map {
                val label = try {
                    pmgr.getApplicationLabel(pmgr.getApplicationInfo(it.packageName, 0)).toString()
                } catch (e: Exception) {
                    it.packageName.substringAfterLast('.')
                }
                AppUsage(label, it.totalTimeInForeground / 60_000)
            }
    }
}
