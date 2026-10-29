package com.speedtester.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Presentation-only snapshot; speed-test calculations and stored results remain unchanged. */
internal data class HomeMetricsSnapshot(
    val download: Double?,
    val upload: Double?,
    val downloadAverage: Double?,
    val downloadPeak: Double?,
    val uploadAverage: Double?,
    val uploadPeak: Double?,
    val loadedPing: Long,
    val loadedJitter: Double?,
    val network: String,
    val ssid: String,
    val bssid: String,
    val connected: Boolean,
    val signal: String,
    val quality: String,
    val linkSpeed: String,
    val frequency: String,
    val serverName: String,
    val serverHost: String,
    val serverLocation: String
)

internal object HomeMetricsFormatting {
    fun speed(value: Double?): String =
        if (value == null || !value.isFinite() || value < 0) "—"
        else String.format(Locale.US, "%.1f", value)

    fun latency(value: Long): String = if (value < 0) "Unavailable" else "$value ms"
    fun identity(value: String): String =
        value.takeUnless { it.isBlank() || it == "—" || it.equals("Unavailable", true) } ?: "Unavailable"
}

private val downloadBlue = Color(0xFF43B8FF)
private val uploadPurple = Color(0xFFAA82FF)

@Composable
private fun SpeedMetricTile(
    label: String,
    value: Double?,
    average: Double?,
    peak: Double?,
    accent: Color,
    modifier: Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(label, color = accent, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall)
            Text(
                HomeMetricsFormatting.speed(value),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("Mbps", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Average  " + HomeMetricsFormatting.speed(average) + " Mbps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text("Peak  " + HomeMetricsFormatting.speed(peak) + " Mbps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun HomeDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HomeDetailGroup(
    title: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
internal fun HomeDetailedMetrics(
    expanded: Boolean,
    onToggle: () -> Unit,
    metrics: HomeMetricsSnapshot,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BarChart, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text("Detailed metrics", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Hide" else "Show")
                    Spacer(Modifier.width(4.dp))
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }

            if (expanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    SpeedMetricTile("↓ Download", metrics.download, metrics.downloadAverage,
                        metrics.downloadPeak, downloadBlue, Modifier.weight(1f))
                    SpeedMetricTile("↑ Upload", metrics.upload, metrics.uploadAverage,
                        metrics.uploadPeak, uploadPurple, Modifier.weight(1f))
                }

                HomeDetailGroup("Connection", {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = downloadBlue)
                }) {
                    HomeDetailLine("Network", metrics.network)
                    if (metrics.network == "Wi-Fi") {
                        HomeDetailLine("Wi-Fi name (SSID)",
                            HomeMetricsFormatting.identity(metrics.ssid))
                        HomeDetailLine("Access point (BSSID)",
                            HomeMetricsFormatting.identity(metrics.bssid))
                    }
                }

                HomeDetailGroup("Internet & signal", {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = downloadBlue)
                }) {
                    HomeDetailLine("Internet", if (metrics.connected) "Available" else "Unavailable")
                    if (metrics.network == "Wi-Fi") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                HomeDetailLine("Signal strength", metrics.signal)
                            }
                            Column(Modifier.weight(1f)) {
                                HomeDetailLine("Signal quality", metrics.quality)
                            }
                        }
                        HomeDetailLine("Link speed", metrics.linkSpeed)
                        HomeDetailLine("Frequency / band", metrics.frequency)
                    }
                    HomeDetailLine("Loaded TCP ping", HomeMetricsFormatting.latency(metrics.loadedPing))
                    HomeDetailLine("Loaded jitter",
                        HomeMetricsFormatting.speed(metrics.loadedJitter) + " ms")
                }

                HomeDetailGroup("Test server", {
                    Icon(Icons.Default.Router, contentDescription = null, tint = downloadBlue)
                }) {
                    HomeDetailLine("Provider", metrics.serverName)
                    HomeDetailLine("Location", metrics.serverLocation)
                    Text(metrics.serverHost.ifBlank { "No test server selected" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
