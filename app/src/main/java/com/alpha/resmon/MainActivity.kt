package com.alpha.resmon

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { MonitorScreen() }
            }
        }
    }
}

@Composable
fun MonitorScreen(vm: MonitorViewModel = viewModel()) {
    val ui by vm.state.collectAsState()
    val owner = LocalLifecycleOwner.current

    // Sample only while the screen is visible.
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) vm.start()
            if (e == Lifecycle.Event.ON_STOP) vm.stop()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.stop() }
    }

    val s = ui.now
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Resource Monitor", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 40.dp)) }

        item {
            MetricCard("Memory (RAM)", "${s.ramUsedMb} / ${s.ramTotalMb} MB  (${s.ramPct.toInt()}%)") {
                LineChart(ui.ramHist, 100f, Color(0xFF4C8DFF))
            }
        }
        item {
            MetricCard("This app: CPU", "${"%.1f".format(s.appCpuPct)}%   |   ${s.appPssMb} MB used") {
                LineChart(ui.cpuHist, 100f, Color(0xFF2FBF71))
            }
        }
        if (s.coreMhz.isNotEmpty()) {
            item {
                MetricCard("CPU core speed", "MHz per core") {
                    Text(s.coreMhz.mapIndexed { i, f -> "C$i $f" }.joinToString("   "))
                }
            }
        }
        item {
            MetricCard("Network", "Down ${"%.1f".format(s.rxKBps)} KB/s   Up ${"%.1f".format(s.txKBps)} KB/s") {
                LineChart(ui.rxHist, max(100f, ui.rxHist.maxOrNull() ?: 0f), Color(0xFFE8A33D))
                LineChart(ui.txHist, max(100f, ui.txHist.maxOrNull() ?: 0f), Color(0xFFD65C8A))
            }
        }
        item {
            MetricCard(
                "Battery",
                "${s.batteryPct}%  ${if (s.charging) "charging" else "discharging"}  |  ${s.currentMa} mA  |  ${"%.1f".format(s.tempC)} °C",
            ) {
                LineChart(ui.tempHist, 60f, Color(0xFFE5534B))
            }
        }
        item {
            MetricCard("Storage", "${"%.1f".format(s.storageUsedGb)} / ${"%.1f".format(s.storageTotalGb)} GB") {
                Text("Thermal state: ${s.thermal}")
            }
        }
        item {
            MetricCard("Top apps today (screen time)", if (ui.hasUsageAccess) "" else "Needs Usage access") {
                if (!ui.hasUsageAccess) {
                    val ctx = LocalContext.current
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                        Text("Grant Usage access")
                    }
                }
            }
        }
        items(ui.topApps) { a ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(a.name)
                Text("${a.minutes} min")
            }
        }
        item { Text("", Modifier.padding(24.dp)) }
    }
}

@Composable
fun MetricCard(title: String, value: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

/** Simple live line graph. Newest value sits at the right edge, history scrolls left. */
@Composable
fun LineChart(values: List<Float>, maxY: Float, color: Color) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        if (values.size < 2) return@Canvas
        val step = size.width / (HISTORY - 1)
        val startX = size.width - step * (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = startX + step * i
            val y = size.height - (v / maxY).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}
