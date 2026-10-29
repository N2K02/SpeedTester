package com.speedtester.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.max

private val wifiPurple = Color(0xFF8978FF)
private val wifiPalette = listOf(
    Color(0xFFAD70EF), Color(0xFF4FA6FF), Color(0xFFFAC23D),
    Color(0xFFED70BD), Color(0xFF53D58B), Color(0xFF59C5C9),
    Color(0xFFE88A54), Color(0xFFB68CFB)
)

private enum class WifiPanel { GRAPH, NETWORKS, BEST, CONNECTION, CHANNELS }

private fun isLocationEnabled(context: Context): Boolean =
    (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun widthOf(result: ScanResult): Int = when (result.channelWidth) {
    ScanResult.CHANNEL_WIDTH_40MHZ -> 40
    ScanResult.CHANNEL_WIDTH_80MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
    ScanResult.CHANNEL_WIDTH_160MHZ -> 160
    5 -> 320 // CHANNEL_WIDTH_320MHZ (introduced API 33)
    else -> 20
}

@Suppress("DEPRECATION")
private fun asWifiPoint(result: ScanResult): WifiAccessPoint = WifiAccessPoint(
    ssid = result.SSID?.takeIf { it.isNotBlank() } ?: "(Hidden network)",
    bssid = result.BSSID.orEmpty(),
    frequencyMhz = result.frequency,
    rssiDbm = result.level,
    widthMhz = widthOf(result),
    capabilities = result.capabilities.orEmpty()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WifiAnalyzerScreen(context: Context, modifier: Modifier = Modifier) {
    val wifi = remember(context) {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }
    var locationGranted by remember { mutableStateOf(hasFineLocation(context)) }
    var locationOn by remember { mutableStateOf(isLocationEnabled(context)) }
    var running by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var inForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { inForeground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { inForeground = false }
    var band by remember { mutableStateOf(WifiBand.TWO_FOUR) }
    var panel by remember { mutableStateOf(WifiPanel.GRAPH) }
    var points by remember { mutableStateOf(emptyList<WifiAccessPoint>()) }
    var status by remember { mutableStateOf("Waiting for Wi-Fi scan") }
    var lastUpdated by remember { mutableLongStateOf(0L) }
    var details by remember { mutableStateOf<WifiAccessPoint?>(null) }

    fun readLatest(updated: Boolean) {
        locationOn = isLocationEnabled(context)
        if (!hasFineLocation(context) || !locationOn) return
        try {
            val results = wifi.scanResults
            points = results.map(::asWifiPoint).filter { it.band != null }
                .distinctBy { it.bssid.ifBlank { it.ssid + it.frequencyMhz } }
            val newest = results.maxOfOrNull { it.timestamp } ?: 0L
            val age = if (newest > 0) ((SystemClock.elapsedRealtimeNanos() / 1000 - newest).coerceAtLeast(0) / 1_000_000) else -1L
            if (updated) {
                lastUpdated = System.currentTimeMillis()
                status = if (results.isEmpty()) "Scan complete · no networks" else
                    if (age >= 0) "Scan complete · results ${age}s old" else "Scan complete"
            } else if (results.isNotEmpty()) {
                status = if (age >= 0) "Cached scan · " + age + "s old" else "Cached Wi-Fi scan"
            } else {
                status = "No Wi-Fi networks detected"
            }
        } catch (e: SecurityException) {
            status = "Precise location permission is required"
        } catch (e: RuntimeException) {
            status = "Could not read scan results: " + (e.message ?: "unknown error")
        }
    }

    fun requestScan() {
        locationGranted = hasFineLocation(context)
        locationOn = isLocationEnabled(context)
        if (!locationGranted || !locationOn) return
        if (!wifi.isWifiEnabled) {
            status = "Enable Wi-Fi to scan networks"
            return
        }
        readLatest(false)
        try {
            if (wifi.startScan()) status = "Scanning nearby access points…"
            else status = "Scan limited by Android · showing cached results"
        } catch (e: SecurityException) {
            status = "Wi-Fi scan permission denied"
        } catch (e: RuntimeException) {
            status = "Scan unavailable: " + (e.message ?: "try again")
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true && hasFineLocation(context)
        locationOn = isLocationEnabled(context)
        if (locationGranted && locationOn) requestScan()
    }

    // Receiver and refresh loop only live while this submenu is on screen. No background scanning.
    DisposableEffect(locationGranted, inForeground, wifi) {
        if (!locationGranted || !inForeground) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    readLatest(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(locationGranted, running, inForeground) {
        if (WifiScanPolicy.shouldScan(locationGranted, running, inForeground, isLocationEnabled(context))) {
            requestScan()
            while (true) {
                delay(30_000L) // Android may throttle scans and return cached results.
                if (WifiScanPolicy.shouldScan(locationGranted, running, inForeground, isLocationEnabled(context))) {
                    requestScan()
                } else {
                    locationOn = isLocationEnabled(context)
                    status = "Turn on Location to scan Wi-Fi"
                }
            }
        }
    }
    val visible = WifiChannelAnalysis.forBand(band, points)
    val ratings = WifiChannelAnalysis.ratings(band, points)
    val best = WifiChannelAnalysis.recommended(band, points)
    val connected = remember(points, lastUpdated, panel) {
        @Suppress("DEPRECATION")
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val name = info?.ssid?.trim('"').orEmpty()
        val bssid = info?.bssid.orEmpty()
        val matched = points.firstOrNull { bssid.isNotBlank() && it.bssid.equals(bssid, true) }
            ?: points.firstOrNull { it.ssid == name && name != "<unknown ssid>" }
        Triple(if (name == "<unknown ssid>" || name.isBlank()) matched?.ssid ?: "Not connected" else name,
            matched, info?.rssi ?: -127)
    }
    val signalCount = points.size

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                WifiBand.entries.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = band == value, onClick = { band = value },
                        shape = SegmentedButtonDefaults.itemShape(index, WifiBand.entries.size),
                        label = { Text(value.label, fontWeight = FontWeight.Bold) }
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = { running = !running }) {
                Icon(if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (running) "Pause periodic scans" else "Resume periodic scans")
            }
            IconButton(onClick = { requestScan() }) {
                Icon(Icons.Default.Refresh, "Refresh Wi-Fi scan")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("GHz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(status, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, modifier = Modifier.weight(1f))
            Text(if (running) "AUTO" else "PAUSED",
                color = if (running) wifiPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(9.dp))
        val panels = listOf(
            Triple(WifiPanel.GRAPH, Icons.Default.BarChart, "Graph"),
            Triple(WifiPanel.NETWORKS, Icons.Default.List, "Networks"),
            Triple(WifiPanel.BEST, Icons.Default.Star, "Best channel"),
            Triple(WifiPanel.CONNECTION, Icons.Default.Router, "Connection"),
            Triple(WifiPanel.CHANNELS, Icons.Default.GridView, "Congestion")
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            panels.forEach { item ->
                Column(
                    Modifier.weight(1f).clickable { panel = item.first }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(item.second, contentDescription = item.third, tint =
                        if (panel == item.first) wifiPurple else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.height(3.dp).fillMaxWidth(0.8f)
                            .background(if (panel == item.first) wifiPurple else Color.Transparent)
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!locationGranted) item {
                AnalyzerMessageCard(
                    "Allow precise location",
                    "Android requires precise location permission to read nearby Wi-Fi networks. SpeedTester does not upload scan results.",
                    "Grant permission", onAction = { locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                )
            } else if (!locationOn) item {
                AnalyzerMessageCard(
                    "Device Location is off",
                    "Enable Location in Android settings to read Wi-Fi scan results.",
                    "Open settings", onAction = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                )
            } else if (!wifi.isWifiEnabled) item {
                AnalyzerMessageCard("Wi-Fi is off", "Enable Wi-Fi before scanning for nearby networks.",
                    "Wi-Fi settings", onAction = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    })
            } else {
                item {
                    Text(band.label + " GHz · " + visible.size + " networks (" + signalCount + " total)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (panel) {
                    WifiPanel.GRAPH -> {
                        item { AnalyzerCard("Channel Graph", "Signal strength (dBm) vs channel") {
                            WifiSignalGraph(visible, band)
                        } }
                        item { BestChannelCard(best, band) }
                        item { Text("Each curve represents one observed access point. Signal levels and widths are estimates.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    WifiPanel.NETWORKS -> {
                        item { Text("Nearby Networks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                        if (visible.isEmpty()) item { EmptyNetworks(band) }
                        items(visible, key = { it.bssid + ":" + it.frequencyMhz }) { ap ->
                            val tint = wifiPalette[visible.indexOf(ap) % wifiPalette.size]
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { details = ap },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Wifi, null, tint = tint, modifier = Modifier.size(30.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(ap.ssid, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                        Text("BSSID: " + ap.bssid.ifBlank { "Unavailable" },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                        Text("Ch " + ap.channel + " · " + ap.widthMhz + " MHz · " + ap.security,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(ap.rssiDbm.toString() + " dBm", color = tint,
                                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    WifiPanel.BEST -> {
                        item { BestChannelCard(best, band) }
                        item {
                            AnalyzerCard("Channel ratings", "Lower interference score is better") {
                                if (best == null) EmptyNetworks(band)
                                else ratings.sortedBy { it.congestion }.take(8).forEach { rating ->
                                    val percent = (rating.congestion / max(1.0, ratings.maxOf { it.congestion })).toFloat()
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Ch " + rating.channel, modifier = Modifier.width(62.dp))
                                        LinearProgressIndicator(
                                            progress = { percent },
                                            modifier = Modifier.weight(1f).height(8.dp),
                                            color = if (rating.channel == best.channel) Color(0xFF49DE86) else wifiPurple
                                        )
                                        Text(" " + rating.affectedNetworks + " APs", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                        item { Text("Estimate based on access points your phone can see. Router channel availability and regulatory limits vary by country.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    }
                    WifiPanel.CONNECTION -> {
                        item {
                            AnalyzerCard("Current Connection", "Wi-Fi details reported by Android") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Wifi, null, tint = wifiPurple, modifier = Modifier.size(38.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(connected.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(12.dp))
                                AnalyzerRow("Signal", if (connected.third == -127) "Unavailable" else connected.third.toString() + " dBm")
                                AnalyzerRow("Channel", connected.second?.channel?.toString() ?: "Unknown")
                                AnalyzerRow("Band", connected.second?.band?.label?.plus(" GHz") ?: "Unknown")
                                AnalyzerRow("Frequency", connected.second?.frequencyMhz?.toString()?.plus(" MHz") ?: "Unknown")
                                AnalyzerRow("Security", connected.second?.security ?: "Unknown")
                                AnalyzerRow("BSSID", connected.second?.bssid ?: "Unavailable")
                            }
                        }
                    }
                    WifiPanel.CHANNELS -> {
                        item {
                            AnalyzerCard("Channel Analysis (" + band.label + " GHz)", "Estimated overlapping Wi-Fi signals per channel") {
                                WifiCongestionChart(ratings, best)
                            }
                        }
                        item { BestChannelCard(best, band) }
                        item { Text("This is an access-point overlap estimate, not measured radio noise or a guarantee of throughput.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    details?.let { ap ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(ap.ssid) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnalyzerRow("Strength", ap.rssiDbm.toString() + " dBm")
                    AnalyzerRow("Channel", ap.channel.toString())
                    AnalyzerRow("Band", ap.band?.label.orEmpty() + " GHz")
                    AnalyzerRow("Frequency", ap.frequencyMhz.toString() + " MHz")
                    AnalyzerRow("Width", ap.widthMhz.toString() + " MHz")
                    AnalyzerRow("Security", ap.security)
                    AnalyzerRow("BSSID", ap.bssid)
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } }
        )
    }
}

@Composable
private fun AnalyzerMessageCard(title: String, message: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.LocationOn, null, tint = wifiPurple)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun AnalyzerCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun AnalyzerRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun EmptyNetworks(band: WifiBand) {
    Text("No " + band.label + " GHz access points in the latest scan. The device may not support this band.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 14.dp))
}

@Composable
private fun BestChannelCard(best: WifiChannelRating?, band: WifiBand) {
    AnalyzerCard("🏆  Recommended Channel", "Lowest observed channel overlap") {
        if (best == null) EmptyNetworks(band)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(best.channel.toString(), color = wifiPurple,
                style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(if (best.congestion < 0.15) "Good" else if (best.congestion < 0.65) "Moderate" else "Busy",
                    fontWeight = FontWeight.Bold,
                    color = if (best.congestion < 0.15) Color(0xFF4BD77B) else Color(0xFFF0C24D))
                Text("Estimated best · " + band.label + " GHz",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WifiSignalGraph(points: List<WifiAccessPoint>, band: WifiBand) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labels = MaterialTheme.colorScheme.onSurfaceVariant
    val minCh = when (band) { WifiBand.TWO_FOUR -> 0f; WifiBand.FIVE -> 32f; WifiBand.SIX -> 0f }
    val maxCh = when (band) { WifiBand.TWO_FOUR -> 15f; WifiBand.FIVE -> 177f; WifiBand.SIX -> 233f }
    val tick = when (band) { WifiBand.TWO_FOUR -> 1; WifiBand.FIVE -> 16; WifiBand.SIX -> 24 }
    Canvas(Modifier.fillMaxWidth().height(315.dp)) {
        val left = 35.dp.toPx()
        val right = 12.dp.toPx()
        val top = 18.dp.toPx()
        val bottom = 28.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        fun x(channel: Float) = left + (channel - minCh) / (maxCh - minCh) * w
        fun y(dbm: Float) = top + ((-20f - dbm) / 75f).coerceIn(0f, 1f) * h
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
            textSize = 10.sp.toPx()
        }
        for (value in -90..-20 step 10) {
            val pos = y(value.toFloat())
            drawLine(grid, Offset(left, pos), Offset(left + w, pos), 1.dp.toPx())
            drawIntoCanvas { it.nativeCanvas.drawText(value.toString(), 0f, pos + 4.dp.toPx(), paint) }
        }
        val tickStart = if (band == WifiBand.FIVE) 36 else 1
        for (channel in tickStart..maxCh.toInt() step tick) {
            val pos = x(channel.toFloat())
            drawLine(grid, Offset(pos, top), Offset(pos, top + h), 0.65.dp.toPx())
            paint.textAlign = android.graphics.Paint.Align.CENTER
            drawIntoCanvas {
                it.nativeCanvas.drawText(channel.toString(), pos, size.height - 7.dp.toPx(), paint)
            }
        }
        points.forEachIndexed { index, ap ->
            val color = wifiPalette[index % wifiPalette.size]
            val center = ap.channel.toFloat()
            val radius = max(2.5f, ap.widthMhz / 10f + 1f)
            val start = maxOf(minCh, center - radius)
            val end = minOf(maxCh, center + radius)
            if (start >= end) return@forEachIndexed
            val peak = ap.rssiDbm.coerceIn(-94, -21).toFloat()
            val shape = Path().apply { moveTo(x(start), y(-95f)) }
            for (step in 0..28) {
                val channel = start + (end - start) * step / 28f
                val ratio = (channel - center) / radius
                val dBm = -95f + (peak + 95f) * (1f - ratio * ratio).coerceIn(0f, 1f)
                shape.lineTo(x(channel), y(dBm))
            }
            shape.lineTo(x(end), y(-95f))
            shape.close()
            drawPath(shape, color.copy(alpha = 0.25f))
            drawPath(shape, color, style = Stroke(width = 1.8.dp.toPx()))
            paint.color = android.graphics.Color.rgb((color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
            paint.textAlign = android.graphics.Paint.Align.CENTER
            paint.textSize = 10.sp.toPx()
            val display = ap.ssid.take(14)
            drawIntoCanvas {
                it.nativeCanvas.drawText(display, x(center).coerceIn(left + 28.dp.toPx(), size.width - 35.dp.toPx()),
                    (y(peak) - 8.dp.toPx()).coerceAtLeast(top + 8.dp.toPx()), paint)
            }
        }
        paint.color = android.graphics.Color.LTGRAY
        paint.textAlign = android.graphics.Paint.Align.CENTER
    }
}

@Composable
private fun WifiCongestionChart(ratings: List<WifiChannelRating>, best: WifiChannelRating?) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(230.dp)) {
        if (ratings.isEmpty()) return@Canvas
        val left = 10.dp.toPx()
        val right = 8.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = 27.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val maxValue = max(1.0, ratings.maxOf { it.congestion })
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
            textSize = 10.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        for (n in 0..4) {
            val y = top + h * n / 4
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1.dp.toPx())
        }
        val gap = 4.dp.toPx()
        val barWidth = w / ratings.size - gap
        ratings.forEachIndexed { index, rating ->
            val x = left + index * w / ratings.size
            val barHeight = (rating.congestion / maxValue).toFloat() * h
            val color = if (rating.channel == best?.channel) Color(0xFF46D982) else
                if (rating.congestion > maxValue * 0.66) Color(0xFFF15C68) else Color(0xFFFAC23D)
            drawRect(color, topLeft = Offset(x + gap / 2, top + h - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight))
            drawIntoCanvas { it.nativeCanvas.drawText(rating.channel.toString(), x + gap / 2 + barWidth / 2,
                size.height - 6.dp.toPx(), paint) }
        }
    }
}
