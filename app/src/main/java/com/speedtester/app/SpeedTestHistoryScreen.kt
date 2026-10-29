package com.speedtester.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

private val downColor = Color(0xFF40B8FF)
private val upColor = Color(0xFFAD6DFF)
private val latencyColor = Color(0xFF47DDB5)
private val pingColor = Color(0xFFF5BF3E)

internal enum class HistoryFilter { ALL, WIFI, MOBILE }
internal enum class HistoryOrder { LATEST, OLDEST, FASTEST, LOWEST_PING }

internal object HistoryViewLogic {
    fun visible(results: List<TestResult>, filter: HistoryFilter, order: HistoryOrder): List<TestResult> {
        val list = results.filter {
            when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.WIFI -> it.networkType.contains("Wi-Fi", true)
                HistoryFilter.MOBILE -> it.networkType.contains("Mobile", true) ||
                    it.networkType.contains("Cellular", true)
            }
        }
        return when (order) {
            HistoryOrder.LATEST -> list.sortedByDescending { it.date }
            HistoryOrder.OLDEST -> list.sortedBy { it.date }
            HistoryOrder.FASTEST -> list.sortedByDescending { it.download }
            HistoryOrder.LOWEST_PING -> list.sortedWith(compareBy<TestResult> {
                if (it.ping < 0) Long.MAX_VALUE else it.ping
            }.thenByDescending { it.date })
        }
    }
}

private fun showDate(date: String): String = runCatching {
    val d = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(date)
    if (d == null) date else SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.getDefault()).format(d)
}.getOrDefault(date)

private fun speed(value: Double) =
    if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "—"

@Composable
private fun HMetric(label: String, value: String, hint: String, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(hint, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryMetrics(item: TestResult) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HMetric("TCP PING", if (item.ping < 0) "—" else item.ping.toString() + " ms",
            "Idle", pingColor, Modifier.weight(1f))
        HMetric("JITTER", speed(item.jitter) + " ms", "Idle", upColor, Modifier.weight(1f))
        HMetric("LOADED", if (item.loadedPing < 0) "—" else item.loadedPing.toString() + " ms",
            "During test", latencyColor, Modifier.weight(1f))
    }
}

@Composable
private fun HistoryResultCard(item: TestResult, open: () -> Unit) {
    Card(onClick = open, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(showDate(item.date), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text((if (item.networkType.contains("Wi-Fi", true)) item.wifiSsid.takeIf { it != "Unavailable" }?.let { "Wi-Fi · " + it } ?: "Wi-Fi" else item.networkType), style = MaterialTheme.typography.labelMedium, color = downColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HMetric("↓ DOWNLOAD", speed(item.download) + " Mbps",
                    "Avg " + speed(item.downloadAverage) + " · Peak " + speed(item.downloadPeak),
                    downColor, Modifier.weight(1f))
                HMetric("↑ UPLOAD", speed(item.upload) + " Mbps",
                    "Avg " + speed(item.uploadAverage) + " · Peak " + speed(item.uploadPeak),
                    upColor, Modifier.weight(1f))
            }
            HorizontalDivider()
            SummaryMetrics(item)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Router, null, Modifier.size(18.dp), tint = downColor)
                Spacer(Modifier.width(8.dp))
                Text(item.server.ifBlank { "Server unknown" }, Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.ChevronRight, "Open result", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun SpeedTestHistoryScreen(
    history: List<TestResult>,
    export: () -> Unit, clear: () -> Unit, open: (TestResult) -> Unit,
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var order by remember { mutableStateOf(HistoryOrder.LATEST) }
    var sorting by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val results = HistoryViewLogic.visible(history, filter, order)
    val wifiCount = history.count { it.networkType.contains("Wi-Fi", true) }
    val mobileCount = history.count {
        it.networkType.contains("Mobile", true) || it.networkType.contains("Cellular", true)
    }
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Recent tests", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(history.size.toString() + " saved results",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = export, enabled = history.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 9.dp)) {
                        Icon(Icons.Default.Description, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("CSV", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = history.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 9.dp)) {
                        Icon(Icons.Default.DeleteOutline, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryFilter.entries.forEach { option ->
                    val count = when (option) {
                        HistoryFilter.ALL -> history.size
                        HistoryFilter.WIFI -> wifiCount
                        HistoryFilter.MOBILE -> mobileCount
                    }
                    FilterChip(selected = filter == option, onClick = { filter = option },
                        modifier = Modifier.weight(1f),
                        label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() } +
                            " (" + count + ")", style = MaterialTheme.typography.labelSmall, maxLines = 1) })
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(onClick = { sorting = true }) {
                    Icon(Icons.Default.Sort, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sort: " + order.name.lowercase().replace('_', ' ')
                        .replaceFirstChar { it.uppercase() })
                }
                DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                    HistoryOrder.entries.forEach { option ->
                        DropdownMenuItem(text = {
                            Text(option.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                        }, onClick = { order = option; sorting = false })
                    }
                }
            }
        }
        if (results.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, tint = downColor)
                    Spacer(Modifier.height(10.dp))
                    Text(if (history.isEmpty()) "No tests yet" else "No tests match this filter")
                    Text(if (history.isEmpty()) "Run a speed test from Home to see the result here."
                        else "Select another network filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(results) { result -> HistoryResultCard(result) { open(result) } }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all history?") },
        text = { Text("Permanently delete all " + history.size + " saved tests?") },
        confirmButton = { TextButton(onClick = { confirmClear = false; clear() }) { Text("Clear all") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
}

@Composable
private fun DetailsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
        Text(value.ifBlank { "Unavailable" }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SpeedTestResultDetails(
    result: TestResult, back: () -> Unit, share: () -> Unit,
    delete: () -> Unit, modifier: Modifier = Modifier
) {
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                Spacer(Modifier.width(6.dp))
                Text("All results")
            }
        }
        item {
            DetailsCard("Test result · " + showDate(result.date)) {
                Text(if (result.networkType.contains("Wi-Fi", true) && result.wifiSsid != "Unavailable") "Wi-Fi · " + result.wifiSsid else result.networkType, color = downColor, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HMetric("↓ DOWNLOAD", speed(result.download) + " Mbps",
                        "Avg " + speed(result.downloadAverage) + " · Peak " + speed(result.downloadPeak),
                        downColor, Modifier.weight(1f))
                    HMetric("↑ UPLOAD", speed(result.upload) + " Mbps",
                        "Avg " + speed(result.uploadAverage) + " · Peak " + speed(result.uploadPeak),
                        upColor, Modifier.weight(1f))
                }
                HorizontalDivider()
                SummaryMetrics(result)
            }
        }
        item {
            DetailsCard("Latency details") {
                DetailLine("TCP ping", if (result.ping < 0) "—" else result.ping.toString() + " ms")
                DetailLine("Jitter", speed(result.jitter) + " ms")
                DetailLine("Loaded TCP ping", if (result.loadedPing < 0) "—" else result.loadedPing.toString() + " ms")
                DetailLine("Loaded jitter", speed(result.loadedJitter) + " ms")
                DetailLine("Failed TCP probes", result.failedProbes.toString() + "/" + result.probeCount)
            }
        }
        item {
            DetailsCard("Connection details") {
                DetailLine("Network", result.networkType)
                DetailLine("Internet", if (result.internetAvailable) "Available" else "Unavailable")
                if (result.networkType.contains("Wi-Fi", true)) {
                    DetailLine("Wi-Fi signal", result.wifiSignal)
                    DetailLine("Signal quality", result.wifiQuality)
                    DetailLine("Wi-Fi name (SSID)", result.wifiSsid)
                    DetailLine("Access point (BSSID)", result.wifiBssid)
                    DetailLine("Link speed", result.wifiLink)
                    DetailLine("Frequency / band", result.wifiFrequency)
                }
                DetailLine("Connection note", result.connectionNote)
            }
        }
        item {
            DetailsCard("Server details") {
                DetailLine("Server", result.server)
                DetailLine("Discovery", result.discoveryStatus)
                DetailLine("Discovery time", if (result.discoveryMs < 0) "—" else result.discoveryMs.toString() + " ms")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = share, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share result")
                }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.DeleteOutline, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this result?") },
        text = { Text("The test will be permanently removed from history.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; delete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}
