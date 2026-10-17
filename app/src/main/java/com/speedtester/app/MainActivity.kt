package com.speedtester.app

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.max

private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

private fun formatHistoryDate(raw: String): String {
    return try {
        val parsed = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(raw)
        if (parsed == null) raw else java.text.SimpleDateFormat("dd/MM/yyyy · HH:mm", java.util.Locale.getDefault()).format(parsed)
    } catch (_: Exception) {
        raw
    }
}

private val Teal = Color(0xFF13B8A6)
private val Muted = Color(0xFF8A9AA5)
data class TestResult(val date: String, val download: Double, val upload: Double, val ping: Long, val jitter: Double, val server: String, val downloadAverage: Double = 0.0, val downloadPeak: Double = 0.0, val uploadAverage: Double = 0.0, val uploadPeak: Double = 0.0, val loadedPing: Long = -1L, val loadedJitter: Double = 0.0, val failedProbes: Int = 0, val probeCount: Int = 0, val networkType: String = "Unknown", val internetAvailable: Boolean = false, val wifiSignal: String = "—", val wifiQuality: String = "—", val wifiLink: String = "—", val wifiFrequency: String = "—", val connectionNote: String = "", val discoveryStatus: String = "Not recorded", val discoveryMs: Long = -1L)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SpeedTesterApp(this) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedTesterApp(context: Context) {
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("speedtester", Context.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf((prefs.getString("endpoint", "https://locate.measurementlab.net/v2/nearest/ndt/ndt7?client_name=SpeedTester&client_version=1.0") ?: "").takeUnless { it.contains("speed.cloudflare.com", ignoreCase = true) } ?: "https://locate.measurementlab.net/v2/nearest/ndt/ndt7?client_name=SpeedTester&client_version=1.0") }
    var availableServers by remember { mutableStateOf<List<NdtServerCandidate>>(emptyList()) }
    var selectedServer by remember { mutableStateOf<NdtServerCandidate?>(null) }
    var serversLoading by remember { mutableStateOf(false) }
    var serversError by remember { mutableStateOf("") }
    var serverSearch by remember { mutableStateOf("") }
    var countryFilter by remember { mutableStateOf("All countries") }
    var serverPage by remember { mutableIntStateOf(0) }
    var countryMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        serversLoading = true
        serversError = ""
        try {
            availableServers = withContext(Dispatchers.IO) { SpeedEngine.discoverCandidates() }
            if (availableServers.isEmpty()) serversError = "No compatible M-Lab servers were found. Refresh to try again."
        } catch (e: Exception) {
            serversError = e.message ?: "Unable to discover compatible servers"
        } finally { serversLoading = false }
    }
    val filteredServers = remember(availableServers, serverSearch, countryFilter) {
        availableServers.filter { candidate ->
            (countryFilter == "All countries" || candidate.countryName.equals(countryFilter, true)) &&
            (serverSearch.isBlank() || listOf(candidate.host, candidate.city, candidate.country, candidate.label).any { it.contains(serverSearch.trim(), true) })
        }.sortedWith(compareBy<NdtServerCandidate> { if (it.country.equals("MU", true)) 0 else 1 }.thenBy { it.distanceKm ?: Double.MAX_VALUE }.thenBy { it.latencyMs ?: Long.MAX_VALUE })
    }
    val serverPageSize = 3
    val serverPageCount = maxOf(1, (filteredServers.size + serverPageSize - 1) / serverPageSize)
    val visibleServers = filteredServers.drop(serverPage * serverPageSize).take(serverPageSize)
    LaunchedEffect(filteredServers.size, serverSearch, countryFilter) { serverPage = 0 }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to test") }
    var progress by remember { mutableFloatStateOf(0f) }
    var download by remember { mutableDoubleStateOf(0.0) }
    var upload by remember { mutableDoubleStateOf(0.0) }
    var ping by remember { mutableLongStateOf(-1L) }
    var jitter by remember { mutableDoubleStateOf(0.0) }
    var downloadAverage by remember { mutableDoubleStateOf(0.0) }
    var downloadPeak by remember { mutableDoubleStateOf(0.0) }
    var uploadAverage by remember { mutableDoubleStateOf(0.0) }
    var uploadPeak by remember { mutableDoubleStateOf(0.0) }
    var loadedPing by remember { mutableLongStateOf(-1L) }
    var loadedJitter by remember { mutableDoubleStateOf(0.0) }
    var failedProbes by remember { mutableIntStateOf(0) }
    var probeCount by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableIntStateOf(0) }
    var updateMessage by remember { mutableStateOf("Tap Check for updates to see the latest release.") }
    var updateDetails by remember { mutableStateOf("") }
    var updateVersion by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    var updateAvailable by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(0f) }
    var updateDownloadedBytes by remember { mutableLongStateOf(0L) }
    var updateTotalBytes by remember { mutableLongStateOf(-1L) }
    val activityLog = remember { mutableStateListOf("Ready. Start a test to see live diagnostics here.") }
    val diagnosticLog = remember { mutableStateListOf<String>() }
    var diagnosticLogExpanded by remember { mutableStateOf(false) }
    var netInfo by remember { mutableStateOf(readNetworkInfo(context)) }
    var serverDetails by remember { mutableStateOf(ServerDetails(prefs.getString("server_host", "").orEmpty(), prefs.getString("server_city", "").orEmpty(), prefs.getString("server_country", "").orEmpty(), prefs.getString("server_lat", null)?.toDoubleOrNull(), prefs.getString("server_lon", null)?.toDoubleOrNull())) }
    var serverDiscoveryMs by remember { mutableLongStateOf(-1L) }
    var serverDiscoveryStatus by remember { mutableStateOf("Not run yet") }
    LaunchedEffect(activeTab, serverDetails.city, serverDetails.country, serverDetails.latitude, serverDetails.longitude) {
        if (activeTab == 2 && serverDetails.latitude == null && serverDetails.longitude == null && serverDetails.city.isNotBlank()) {
            val coords = withContext(Dispatchers.IO) { ServerGeoLocator.locate(serverDetails.city, serverDetails.country) }
            if (coords != null) {
                val mapped = serverDetails.copy(latitude = coords.first, longitude = coords.second)
                serverDetails = mapped
                prefs.edit().putString("server_lat", mapped.latitude.toString()).putString("server_lon", mapped.longitude.toString()).apply()
            }
        }
    }
    var downloadTcp by remember { mutableStateOf<NdtTcpInfo?>(null) }
    var uploadTcp by remember { mutableStateOf<NdtTcpInfo?>(null) }
    var serverIp by remember { mutableStateOf("") }
    var dnsLookupMs by remember { mutableLongStateOf(-1L) }
    var regionalBenchmark by remember { mutableStateOf<RegionalBenchmark?>(null) }
    var regionalLoading by remember { mutableStateOf(false) }
    var regionalMessage by remember { mutableStateOf("Load public M-Lab measurements for Mauritius to compare your results.") }
    var loggedPhase by remember { mutableStateOf("") }
    var livePing by remember { mutableLongStateOf(-1L) }
    var liveJitter by remember { mutableDoubleStateOf(0.0) }
    fun logDiagnostic(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        diagnosticLog.add("$stamp  $message")
        while (diagnosticLog.size > 500) diagnosticLog.removeAt(0)
    }
    val downloadSamples = remember { mutableStateListOf<Float>() }
    val uploadSamples = remember { mutableStateListOf<Float>() }
    val history = remember {
        runCatching {
            val arr = org.json.JSONArray(prefs.getString("history", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TestResult(o.optString("date"), o.optDouble("download"), o.optDouble("upload"), o.optLong("ping", -1), o.optDouble("jitter"), o.optString("server"), o.optDouble("downloadAverage", 0.0).finiteOrZero(), o.optDouble("downloadPeak", 0.0).finiteOrZero(), o.optDouble("uploadAverage", 0.0).finiteOrZero(), o.optDouble("uploadPeak", 0.0).finiteOrZero(), o.optLong("loadedPing", -1), o.optDouble("loadedJitter", 0.0).finiteOrZero(), o.optInt("failedProbes"), o.optInt("probeCount"), o.optString("networkType", "Unknown"), o.optBoolean("internetAvailable", false), o.optString("wifiSignal", "—"), o.optString("wifiQuality", "—"), o.optString("wifiLink", "—"), o.optString("wifiFrequency", "—"), o.optString("connectionNote", ""), o.optString("discoveryStatus", "Not recorded"), o.optLong("discoveryMs", -1))
            }.toMutableStateList()
        }.getOrElse { mutableStateListOf() }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Color(0xFF0B1117), surface = Color(0xFF141D25), onSurface = Color(0xFFEAF2F5))) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Column { Text("SpeedTester", fontWeight = FontWeight.Bold); Text("Network diagnostics", style = MaterialTheme.typography.labelSmall, color = Muted) } },
                    actions = {},
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B1117)))
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF101820)) {
                    NavigationBarItem(selected = activeTab == 0, onClick = { activeTab = 0 }, icon = { Icon(Icons.Default.NetworkCheck, null) }, label = { Text("Test") })
                    NavigationBarItem(selected = activeTab == 1, onClick = { activeTab = 1 }, icon = { Icon(Icons.Default.History, null) }, label = { Text("History") })
                    NavigationBarItem(selected = activeTab == 2, onClick = { activeTab = 2 }, icon = { Icon(Icons.Default.Bolt, null) }, label = { Text("Server") })
                    NavigationBarItem(selected = activeTab == 3, onClick = { activeTab = 3 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                    NavigationBarItem(selected = activeTab == 4, onClick = { activeTab = 4 }, icon = { Icon(Icons.Default.Update, null) }, label = { Text("Updates") })
                }
            }, containerColor = Color(0xFF0B1117)
        ) { padding ->
            when (activeTab) {
                0 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF14232B)), shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("MAURITIUS PROVIDER TESTS", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("Use these separately for a local cross-check. Their results are not imported into SpeedTester history, and their measurements may use different methods or servers.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://kalitenet.icta.mu"))) } }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Open KaliteNet · ICTA / nPerf")
                                }
                                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://speedtest.telecom.mu"))) } }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Open My.t / Mauritius Telecom speed test")
                                }
                                Text("Emtel: no official public speed-test endpoint was verified during this review. We will not label an unverified server as an Emtel server.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.nperf.com/en/product/nperf-engine-sdk"))) } }) {
                                    Text("nPerf SDK integration information")
                                }
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25)), shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("MAURITIUS SERVER SELECTOR", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        Text("Compatible test servers · local first", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            serversLoading = true; serversError = ""
                                            try {
                                                val refreshed = withContext(Dispatchers.IO) { SpeedEngine.discoverCandidates() }
                                                availableServers = refreshed
                                                if (refreshed.isEmpty()) serversError = "No compatible M-Lab servers were found."
                                                if (selectedServer != null && refreshed.none { it.host == selectedServer?.host }) selectedServer = null
                                            } catch (e: Exception) { serversError = e.message ?: "Server refresh failed" }
                                            finally { serversLoading = false }
                                        }
                                    }, enabled = !serversLoading && !testing) { Text("Refresh") }
                                }
                                Text("Sorted with Mauritius first, then by estimated distance from the island and measured TCP latency. These are M-Lab NDT7-compatible servers, not a claim that they are hosted by My.t or Emtel.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(value = serverSearch, onValueChange = { serverSearch = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search server, city, host") }, placeholder = { Text("e.g. Mauritius, Nairobi") })
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box {
                                        OutlinedButton(onClick = { countryMenuExpanded = true }) { Text(countryFilter) }
                                        DropdownMenu(expanded = countryMenuExpanded, onDismissRequest = { countryMenuExpanded = false }) {
                                            (listOf("All countries") + availableServers.map { it.countryName }.filter { it.isNotBlank() }.distinct().sorted()).forEach { country ->
                                                DropdownMenuItem(text = { Text(country) }, onClick = { countryFilter = country; countryMenuExpanded = false })
                                            }
                                        }
                                    }
                                    Text("${filteredServers.size} server(s) · max 10", color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                if (serversLoading) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()); Text("Finding compatible servers…", color = Muted, style = MaterialTheme.typography.bodySmall) }
                                if (serversError.isNotBlank()) Text(serversError, color = Color(0xFFFFA64D), style = MaterialTheme.typography.bodySmall)
                                if (!serversLoading && visibleServers.isEmpty()) Text("No servers match this search/filter. Try All countries or refresh.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                visibleServers.forEach { candidate ->
                                    val isSelected = selectedServer?.host == candidate.host
                                    Card(
                                        onClick = { if (!testing) selectedServer = candidate },
                                        enabled = !testing,
                                        colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF173B3B) else Color(0xFF0D151C)),
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Teal) else null,
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            RadioButton(selected = isSelected, onClick = { if (!testing) selectedServer = candidate }, enabled = !testing)
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(candidate.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                                                Text(candidate.host, color = Muted, style = MaterialTheme.typography.labelSmall)
                                                Text(listOf(candidate.city, candidate.countryName).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Location not provided" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                                                Text("Distance: ${candidate.distanceKm?.let { "${it.toInt()} km from Mauritius" } ?: "unknown"} · TCP latency: ${candidate.latencyMs?.let { "${it} ms" } ?: "unavailable"}", color = Muted, style = MaterialTheme.typography.labelSmall)
                                            }
                                            if (isSelected) Icon(Icons.Default.Bolt, contentDescription = "Selected server", tint = Teal)
                                        }
                                    }
                                }
                                if (serverPageCount > 1) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { serverPage = (serverPage - 1).coerceAtLeast(0) }, enabled = serverPage > 0) { Text("Previous") }
                                        Text("Page ${serverPage + 1} of ${serverPageCount}", color = Muted, style = MaterialTheme.typography.labelMedium)
                                        TextButton(onClick = { serverPage = (serverPage + 1).coerceAtMost(serverPageCount - 1) }, enabled = serverPage < serverPageCount - 1) { Text("Next") }
                                    }
                                }
                                Text(if (selectedServer == null) "Select a server to enable the speed test." else "Selected: ${selectedServer?.label}", color = if (selectedServer == null) Muted else Teal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25)), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(status, color = Muted, style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(String.format(java.util.Locale.US, "%.1f", download), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White); Text("↓ DOWNLOAD", color = Color(0xFF32A8FF), style = MaterialTheme.typography.labelMedium); Text("Mbps", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(String.format(java.util.Locale.US, "%.1f", upload), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White); Text("↑ UPLOAD", color = Color(0xFFFFA64D), style = MaterialTheme.typography.labelMedium); Text("Mbps", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                    Column(Modifier.weight(0.7f), horizontalAlignment = Alignment.CenterHorizontally) { Text(if (livePing >= 0) livePing.toString() else if (ping >= 0) ping.toString() else "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White); Text("PING", color = Muted, style = MaterialTheme.typography.labelMedium); Text("ms", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                    Column(Modifier.weight(0.7f), horizontalAlignment = Alignment.CenterHorizontally) { Text(String.format(java.util.Locale.US, "%.1f", liveJitter.takeIf { it > 0 } ?: jitter), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White); Text("JITTER", color = Muted, style = MaterialTheme.typography.labelMedium); Text("ms", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                }
                                Spacer(Modifier.height(10.dp))
                                BandwidthChart(downloadSamples.toList(), uploadSamples.toList(), Modifier.fillMaxWidth().height(170.dp))
                                Text("Scale: 0–${max(10f, (downloadSamples + uploadSamples).maxOrNull() ?: 10f).toInt()} Mbps · live rolling samples", color = Muted, style = MaterialTheme.typography.labelSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { Text("━ Download", color = Color(0xFF32A8FF), style = MaterialTheme.typography.labelSmall); Text("━ Upload", color = Color(0xFFFFA64D), style = MaterialTheme.typography.labelSmall); Text("Live · Mbps", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                     StatTile("DOWNLOAD AVG", "%.1f".format(downloadAverage), "Mbps", Color(0xFF32A8FF), Modifier.weight(1f))
                                     StatTile("DOWNLOAD PEAK", "%.1f".format(downloadPeak), "Mbps", Color(0xFF32A8FF), Modifier.weight(1f))
                                 }
                                 Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                     StatTile("UPLOAD AVG", "%.1f".format(uploadAverage), "Mbps", Color(0xFFFFA64D), Modifier.weight(1f))
                                     StatTile("UPLOAD PEAK", "%.1f".format(uploadPeak), "Mbps", Color(0xFFFFA64D), Modifier.weight(1f))
                                 }
                                 Text("Stability · loaded ping " + (if (loadedPing >= 0) "$loadedPing ms" else "—") + " · jitter %.1f ms · failed probes %d/%d".format(loadedJitter, failedProbes, probeCount), color = Muted, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(18.dp))
                                Button(onClick = {
                                    if (!testing) {
                                        testing = true; error = null; progress = 0f; serverDiscoveryMs = -1L; serverDiscoveryStatus = "Discovering…"; loggedPhase = ""; livePing = -1L; liveJitter = 0.0; downloadAverage = 0.0; downloadPeak = 0.0; uploadAverage = 0.0; uploadPeak = 0.0; loadedPing = -1L; loadedJitter = 0.0; failedProbes = 0; probeCount = 0; downloadSamples.clear(); uploadSamples.clear(); status = "Preparing test…"; activityLog.clear(); activityLog.add("Validating server URL"); activityLog.add("Preparing server connection and TCP latency probes"); activityLog.add("Target: " + endpoint.trim()); while (activityLog.size > 8) activityLog.removeAt(0)
                                        diagnosticLog.clear()
                                        logDiagnostic("========== SPEED TEST START ==========")
                                        logDiagnostic("Target URL: " + endpoint.trim())
                                        logDiagnostic("Network: " + netInfo.type + " | internet=" + netInfo.connected + " | Wi-Fi RSSI=" + netInfo.signal + " | quality=" + netInfo.quality + " | link=" + netInfo.link + " | band=" + netInfo.frequency)
                                        logDiagnostic("Device clock: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", java.util.Locale.getDefault()).format(java.util.Date()))
                                        scope.launch {
                                            try {
                                                val result = SpeedEngine.run(endpoint.trim().trimEnd('/'), selectedServer, { server ->
                                                    scope.launch {
                                                        serverDetails = server
                                                        prefs.edit().putString("server_host", server.host).putString("server_city", server.city).putString("server_country", server.country).apply()
                                                        val coords = withContext(Dispatchers.IO) { ServerGeoLocator.locate(server.city, server.country) }
                                                        val mapped = server.copy(latitude = coords?.first, longitude = coords?.second)
                                                        serverDetails = mapped
                                                        prefs.edit().putString("server_lat", mapped.latitude?.toString()).putString("server_lon", mapped.longitude?.toString()).apply()
                                                    }
                                                }, { phase, value, mbps ->
                                                    status = phase; progress = value
                                                    if (loggedPhase != phase) { loggedPhase = phase; activityLog.add(phase + " phase started"); while (activityLog.size > 8) activityLog.removeAt(0) }
                                                    if (mbps > 0f) { val isUpload = phase.contains("Upload", true); val target = if (isUpload) uploadSamples else downloadSamples; target.add(mbps); if (target.size > 80) target.removeAt(0); if (isUpload) { uploadAverage = target.average(); uploadPeak = (target.maxOrNull() ?: mbps).toDouble() } else { downloadAverage = target.average(); downloadPeak = (target.maxOrNull() ?: mbps).toDouble() }; val line = phase + " live rate: " + String.format(java.util.Locale.US, "%.1f Mbps", mbps); if (activityLog.lastOrNull()?.startsWith(phase + " live rate:") == true) activityLog[activityLog.lastIndex] = line else { activityLog.add(line); if (activityLog.size > 8) activityLog.removeAt(0) } }
                                                }, { p, j -> ping = p; jitter = j; livePing = p; liveJitter = j }, { message -> scope.launch { logDiagnostic(message); when { message.startsWith("DISCOVERY TIME:") -> serverDiscoveryMs = message.substringAfter(":").trim().removeSuffix("ms").toLongOrNull() ?: -1L; message.startsWith("DISCOVERY STATUS:") -> serverDiscoveryStatus = message.substringAfter(":").trim() } } })
                                                download = result.download; upload = result.upload; ping = result.ping; jitter = result.jitter
                                                downloadTcp = result.downloadTcp; uploadTcp = result.uploadTcp
                                                serverIp = result.serverIp; dnsLookupMs = result.dnsLookupMs
                                                downloadAverage = result.downloadAverage; downloadPeak = result.downloadPeak; uploadAverage = result.uploadAverage; uploadPeak = result.uploadPeak
                                                loadedPing = result.loadedPing; loadedJitter = result.loadedJitter; failedProbes = result.failedProbes; probeCount = result.probeCount
                                                val connectionSnapshot = readNetworkInfo(context)
                                                val saved = TestResult(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), download, upload, ping, jitter, listOf(serverDetails.host, serverDetails.location).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { endpoint }, downloadAverage, downloadPeak, uploadAverage, uploadPeak, loadedPing, loadedJitter, failedProbes, probeCount, connectionSnapshot.type, connectionSnapshot.connected, connectionSnapshot.signal, connectionSnapshot.quality, connectionSnapshot.link, connectionSnapshot.frequency, connectionSnapshot.note, serverDiscoveryStatus, serverDiscoveryMs)
                                                history.add(0, saved); while (history.size > 100) history.removeAt(history.lastIndex)
                                                val arr = org.json.JSONArray()
                                                history.forEach { x -> arr.put(JSONObject().put("date", x.date).put("download", x.download.finiteOrZero()).put("upload", x.upload.finiteOrZero()).put("ping", x.ping).put("jitter", x.jitter.finiteOrZero()).put("server", x.server).put("downloadAverage", x.downloadAverage.finiteOrZero()).put("downloadPeak", x.downloadPeak.finiteOrZero()).put("uploadAverage", x.uploadAverage.finiteOrZero()).put("uploadPeak", x.uploadPeak.finiteOrZero()).put("loadedPing", x.loadedPing).put("loadedJitter", x.loadedJitter.finiteOrZero()).put("failedProbes", x.failedProbes).put("probeCount", x.probeCount).put("networkType", x.networkType).put("internetAvailable", x.internetAvailable).put("wifiSignal", x.wifiSignal).put("wifiQuality", x.wifiQuality).put("wifiLink", x.wifiLink).put("wifiFrequency", x.wifiFrequency).put("connectionNote", x.connectionNote).put("discoveryStatus", x.discoveryStatus).put("discoveryMs", x.discoveryMs)) }
                                                prefs.edit().putString("history", arr.toString()).apply(); logDiagnostic("RESULT download=" + "%.2f".format(java.util.Locale.US, download) + " Mbps, upload=" + "%.2f".format(java.util.Locale.US, upload) + " Mbps, idlePing=" + ping + " ms, jitter=" + "%.2f".format(java.util.Locale.US, jitter) + " ms"); logDiagnostic("Stability: loadedPing=" + loadedPing + " ms, loadedJitter=" + "%.2f".format(java.util.Locale.US, loadedJitter) + " ms, failedProbes=" + failedProbes + "/" + probeCount); logDiagnostic("========== SPEED TEST SUCCESS =========="); status = "Test complete"; activityLog.add("Test completed successfully"); activityLog.add("Download: " + String.format(java.util.Locale.US, "%.1f Mbps", download) + " · Upload: " + String.format(java.util.Locale.US, "%.1f Mbps", upload)); activityLog.add("Loaded TCP ping: " + (if (loadedPing >= 0) "$loadedPing ms" else "unavailable") + " · failed probes $failedProbes/$probeCount"); while (activityLog.size > 8) activityLog.removeAt(0)
                                            } catch (e: Exception) { if (serverDiscoveryStatus == "Discovering…") serverDiscoveryStatus = "Failed"; logDiagnostic("FATAL " + e.javaClass.name + ": " + (e.message ?: "Test failed")); e.cause?.let { logDiagnostic("CAUSE " + it.javaClass.name + ": " + (it.message ?: "no message")) }; e.stackTrace.take(8).forEach { logDiagnostic("  at " + it.toString()) }; error = e.message ?: "Test failed"; status = "Test failed"; activityLog.add("ERROR: " + (e.message ?: "Test failed")); while (activityLog.size > 8) activityLog.removeAt(0) }
                                            finally { testing = false; progress = 1f; netInfo = readNetworkInfo(context) }
                                        }
                                    }
                                }, enabled = !testing && selectedServer != null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(if (testing) "TEST RUNNING…" else if (selectedServer == null) "SELECT A SERVER FIRST" else "START SPEED TEST", fontWeight = FontWeight.Bold) }
                                if (testing) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    item { Text("LIVE DIAGNOSTICS", color = Muted, style = MaterialTheme.typography.labelLarge) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25)), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Timeline, null, tint = Teal); Spacer(Modifier.width(8.dp)); Text("Latest activity", fontWeight = FontWeight.SemiBold) }
                                Spacer(Modifier.height(8.dp))
                                Column(Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 176.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    activityLog.takeLast(8).forEach { line -> Text("• " + line, color = if (line.startsWith("ERROR")) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                    item { Text("CONNECTION", color = Muted, style = MaterialTheme.typography.labelLarge) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25))) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                InfoRow("Discovery status", serverDiscoveryStatus)
                                InfoRow("Discovery time", if (serverDiscoveryMs >= 0) "$serverDiscoveryMs ms" else "—")
                                InfoRow("Test server", serverDetails.host.ifBlank { "Not selected yet" })
                                InfoRow("Server location", serverDetails.location.ifBlank { "Not available yet" })
                                InfoRow("Selected-server ping", if (ping >= 0) "$ping ms" else "Measured during test")
                                Text("M-Lab selects an available nearby server; nearest does not always mean fastest.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                InfoRow("Protocol", "M-Lab NDT7 · secure WebSocket")
                                 InfoRow("Network", netInfo.type); InfoRow("Internet capability", if (netInfo.connected) "Available" else "Unavailable")
                                InfoRow("Wi-Fi signal", netInfo.signal); InfoRow("Signal quality", netInfo.quality); InfoRow("Wi-Fi link speed", netInfo.link); InfoRow("Frequency / band", netInfo.frequency); InfoRow("Connection note", netInfo.note)
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25)), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Timeline, null, tint = Teal)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Detailed diagnostic log", fontWeight = FontWeight.SemiBold)
                                        Text("${diagnosticLog.size} entries · keeps latest 500 · shows latest 30", color = Muted, style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { diagnosticLogExpanded = !diagnosticLogExpanded }) {
                                        Icon(if (diagnosticLogExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (diagnosticLogExpanded) "Collapse diagnostic log" else "Expand diagnostic log", tint = Teal)
                                    }
                                }
                                if (diagnosticLogExpanded) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("SpeedTester diagnostic log", diagnosticLog.joinToString("\n")))
                                        }) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Copy") }
                                        TextButton(onClick = { diagnosticLog.clear() }) { Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Clear") }
                                    }
                                    Column(Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (diagnosticLog.isEmpty()) Text("Log is empty. Start a test to capture detailed diagnostics.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                        diagnosticLog.takeLast(30).forEach { line ->
                                            Text(line, color = if (line.contains("FATAL") || line.contains("HTTP 4") || line.contains("ERROR")) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Text("Showing latest 8 lines. Copy exports the full in-memory log.", color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    if (updateMessage.isNotBlank()) item { Text(updateMessage, color = Teal, style = MaterialTheme.typography.bodySmall) }
                }
                1 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("Recent tests", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    item {
                        Row {
                            OutlinedButton(onClick = {
                                val csv = buildString { append("date,download_mbps,upload_mbps,download_average_mbps,download_peak_mbps,upload_average_mbps,upload_peak_mbps,ping_ms,jitter_ms,loaded_ping_ms,loaded_jitter_ms,failed_tcp_probes,total_tcp_probes,server,network_type,internet_available,wifi_signal,wifi_quality,wifi_link_speed,wifi_frequency,connection_note,discovery_status,discovery_ms\n"); history.forEach { val safeServer = it.server.replace("\"", "\"\""); append("\"" + it.date + "\"," + it.download + "," + it.upload + "," + it.downloadAverage + "," + it.downloadPeak + "," + it.uploadAverage + "," + it.uploadPeak + "," + it.ping + "," + it.jitter + "," + it.loadedPing + "," + it.loadedJitter + "," + it.failedProbes + "," + it.probeCount + ",\"" + safeServer + "\",\"" + it.networkType + "\"," + it.internetAvailable + ",\"" + it.wifiSignal + "\",\"" + it.wifiQuality + "\",\"" + it.wifiLink + "\",\"" + it.wifiFrequency + "\",\"" + it.connectionNote.replace("\"", "\"\"") + "\",\"" + it.discoveryStatus + "\"," + it.discoveryMs + "\n") } }
                                val file = File(context.cacheDir, "speedtester-history.csv"); file.writeText(csv)
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export history"))
                            }) { Text("Export CSV") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                history.clear()
                                prefs.edit().putString("history", "[]").apply()
                            }) { Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Clear all history") }
                        }
                    }
                    if (history.isEmpty()) item { Text("Your completed tests will appear here.", color = Muted) }
                    items(history) { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25))) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(formatHistoryDate(item.date), color = Muted, style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("DOWNLOAD", color = Color(0xFF42C9F5), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        Text("%.1f Mbps".format(item.download), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text("Avg %.1f · Peak %.1f".format(item.downloadAverage, item.downloadPeak), color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text("UPLOAD", color = Color(0xFF8BE28B), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        Text("%.1f Mbps".format(item.upload), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text("Avg %.1f · Peak %.1f".format(item.uploadAverage, item.uploadPeak), color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF293640))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Ping", color = Muted, style = MaterialTheme.typography.labelSmall)
                                        Text("${item.ping} ms", fontWeight = FontWeight.SemiBold)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text("Jitter", color = Muted, style = MaterialTheme.typography.labelSmall)
                                        Text("%.1f ms".format(item.jitter), fontWeight = FontWeight.SemiBold)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text("Loaded ping", color = Muted, style = MaterialTheme.typography.labelSmall)
                                        Text(if (item.loadedPing >= 0) "${item.loadedPing} ms" else "—", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Text("Loaded jitter %.1f ms  ·  Failed probes %d/%d".format(item.loadedJitter, item.failedProbes, item.probeCount), color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text("Server", color = Muted, style = MaterialTheme.typography.labelSmall)
                                Text(item.server.ifBlank { "Server details unavailable" }, style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider(color = Color(0xFF293640))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.NetworkCheck, null, tint = Teal, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("CONNECTION DETAILS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                }
                                InfoRow("Network", item.networkType)
                                InfoRow("Internet capability", if (item.internetAvailable) "Available" else "Unavailable")
                                InfoRow("Wi-Fi signal", item.wifiSignal)
                                InfoRow("Signal quality", item.wifiQuality)
                                InfoRow("Wi-Fi link speed", item.wifiLink)
                                InfoRow("Frequency / band", item.wifiFrequency)
                                InfoRow("Connection note", item.connectionNote.ifBlank { "No additional note" })
                                InfoRow("Server discovery", item.discoveryStatus)
                                InfoRow("Discovery time", if (item.discoveryMs >= 0) "${item.discoveryMs} ms" else "—")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { shareTestResult(context, item) }) { Text("Share result") }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        history.remove(item)
                                        val updatedHistory = org.json.JSONArray()
                                        history.forEach { x -> updatedHistory.put(JSONObject().put("date", x.date).put("download", x.download.finiteOrZero()).put("upload", x.upload.finiteOrZero()).put("ping", x.ping).put("jitter", x.jitter.finiteOrZero()).put("server", x.server).put("downloadAverage", x.downloadAverage.finiteOrZero()).put("downloadPeak", x.downloadPeak.finiteOrZero()).put("uploadAverage", x.uploadAverage.finiteOrZero()).put("uploadPeak", x.uploadPeak.finiteOrZero()).put("loadedPing", x.loadedPing).put("loadedJitter", x.loadedJitter.finiteOrZero()).put("failedProbes", x.failedProbes).put("probeCount", x.probeCount).put("networkType", x.networkType).put("internetAvailable", x.internetAvailable).put("wifiSignal", x.wifiSignal).put("wifiQuality", x.wifiQuality).put("wifiLink", x.wifiLink).put("wifiFrequency", x.wifiFrequency).put("connectionNote", x.connectionNote).put("discoveryStatus", x.discoveryStatus).put("discoveryMs", x.discoveryMs)) }
                                        prefs.edit().putString("history", updatedHistory.toString()).apply()
                                    }) {
                                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Server map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25)), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Bolt, null, tint = Teal, modifier = Modifier.size(26.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Connected M-Lab server", fontWeight = FontWeight.Bold)
                                        Text(serverDetails.location.ifBlank { "Run a speed test to discover a server" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (serverDetails.latitude != null && serverDetails.longitude != null) {
                                    ServerMapWebView(serverDetails.latitude!!, serverDetails.longitude!!, Modifier.fillMaxWidth().height(280.dp))
                                    Text("Marker shows the approximate city location, not the exact physical server.", color = Muted, style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Box(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF0D151B), RoundedCornerShape(14.dp)).padding(18.dp), contentAlignment = Alignment.Center) {
                                        Text(if (serverDetails.host.isBlank()) "The map appears after your first successful server discovery." else "Map coordinates are not available yet. Connect to the internet and run a test to look up the server city.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF26343E))
                                InfoRow("Server hostname", serverDetails.host.ifBlank { "—" })
                                InfoRow("City / country", serverDetails.location.ifBlank { "—" })
                                InfoRow("Test protocol", "M-Lab NDT7 over WSS")
                                InfoRow("Download", "%.1f Mbps".format(java.util.Locale.US, download))
                                InfoRow("Upload", "%.1f Mbps".format(java.util.Locale.US, upload))
                                InfoRow("Idle TCP latency", if (ping >= 0) "$ping ms" else "—")
                                InfoRow("Jitter", if (ping >= 0) "%.1f ms".format(java.util.Locale.US, jitter) else "—")
                                InfoRow("Network", netInfo.type)
                                InfoRow("Resolved server IP", serverIp.ifBlank { "Not resolved yet" })
                                InfoRow("DNS lookup", if (dnsLookupMs >= 0L) "$dnsLookupMs ms" else "—")
                                val quality = assessQuality(download, upload, ping, jitter, loadedPing, failedProbes, probeCount)
                                HorizontalDivider(color = Color(0xFF26343E))
                                Text("INTERNET QUALITY", color = Muted, style = MaterialTheme.typography.labelLarge)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp).background(Color(0xFF0D151B), RoundedCornerShape(18.dp))) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(if (quality.score > 0) quality.score.toString() else "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Teal)
                                            Text("/100", style = MaterialTheme.typography.labelSmall, color = Muted)
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(quality.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(quality.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                HorizontalDivider(color = Color(0xFF26343E))
                                Text("ADVANCED TCP DIAGNOSTICS", color = Muted, style = MaterialTheme.typography.labelLarge)
                                Text("Server-reported TCP_INFO values are available only when the selected M-Lab server sends them.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text("Download direction", fontWeight = FontWeight.SemiBold)
                                TcpInfoRows(downloadTcp)
                                Text("Upload direction", fontWeight = FontWeight.SemiBold)
                                TcpInfoRows(uploadTcp)
                                HorizontalDivider(color = Color(0xFF26343E))
                                Text("CONNECTION PATH", color = Muted, style = MaterialTheme.typography.labelLarge)
                                InfoRow("DNS resolution", if (dnsLookupMs >= 0L) "Completed in $dnsLookupMs ms" else "Not measured")
                                InfoRow("TCP connect latency", if (ping >= 0L) "Average $ping ms" else "Not measured")
                                Text("NDT7 Locate does not expose a hop-by-hop traceroute. These are endpoint diagnostics, not a map of every router between you and M-Lab.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider(color = Color(0xFF26343E))
                                Text("YOUR RECENT BASELINE", color = Muted, style = MaterialTheme.typography.labelLarge)
                                val previous = history.drop(1).take(10)
                                if (previous.isNotEmpty()) {
                                    InfoRow("Recent download average", "%.1f Mbps".format(java.util.Locale.US, previous.map { it.download }.average()))
                                    InfoRow("Recent upload average", "%.1f Mbps".format(java.util.Locale.US, previous.map { it.upload }.average()))
                                    InfoRow("Current download vs baseline", if (previous.map { it.download }.average() > 0.0) "%+.0f%%".format(java.util.Locale.US, (download / previous.map { it.download }.average() - 1.0) * 100.0) else "Not enough data")
                                    InfoRow("Previous tests used", previous.size.toString())
                                } else Text("Run more tests to build a personal baseline.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                HorizontalDivider(color = Color(0xFF26343E))
                                Text("M-LAB REGIONAL RESEARCH", color = Muted, style = MaterialTheme.typography.labelLarge)
                                Text("M-Lab's public Statistics API publishes country-level daily aggregates as JSON, so SpeedTester can compare your test against the recent Mauritius results without a BigQuery key.", color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (regionalLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(regionalMessage, color = Muted, style = MaterialTheme.typography.bodySmall)
                                regionalBenchmark?.let { benchmark ->
                                    Text("Mauritius · recent published daily medians", fontWeight = FontWeight.SemiBold)
                                    InfoRow("Reference period", benchmark.firstDate + " to " + benchmark.lastDate)
                                    InfoRow("M-Lab median download", "%.1f Mbps".format(java.util.Locale.US, benchmark.downloadMedian))
                                    InfoRow("M-Lab median upload", "%.1f Mbps".format(java.util.Locale.US, benchmark.uploadMedian))
                                    InfoRow("M-Lab median minimum RTT", benchmark.pingMedian?.let { "%.1f ms".format(java.util.Locale.US, it) } ?: "—")
                                    InfoRow("Download vs regional median", if (download > 0.0) "%+.0f%%".format(java.util.Locale.US, (download / benchmark.downloadMedian - 1.0) * 100.0) else "Run a test first")
                                    InfoRow("Upload vs regional median", if (upload > 0.0) "%+.0f%%".format(java.util.Locale.US, (upload / benchmark.uploadMedian - 1.0) * 100.0) else "Run a test first")
                                    Text("Approx. " + benchmark.sampleCount + " download measurements across the selected period. This is a regional reference, not an ISP-specific guarantee.", color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                                Button(onClick = {
                                    if (!regionalLoading) scope.launch {
                                        regionalLoading = true
                                        regionalMessage = "Fetching public M-Lab statistics…"
                                        val result = runCatching { withContext(Dispatchers.IO) { RegionalBenchmarkClient.loadMauritius() } }
                                        regionalBenchmark = result.getOrNull()
                                        regionalMessage = if (result.isSuccess) "Regional data loaded from M-Lab's public Statistics API." else (result.exceptionOrNull()?.message ?: "Regional data unavailable.")
                                        regionalLoading = false
                                    }
                                }, enabled = !regionalLoading, modifier = Modifier.fillMaxWidth()) { Text(if (regionalLoading) "Loading regional data…" else "Load Mauritius benchmark") }
                                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.measurementlab.net/data/docs/"))) }) { Text("Explore M-Lab data documentation") }
                                Text("M-Lab chooses an available nearby server automatically. Server city coordinates are approximate; map tiles are provided by OpenStreetMap.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                3 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    item { Text("M-Lab NDT7 server discovery URL", color = Muted) }
                    item { OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server URL") }) }
                    item {
                        Button(onClick = {
                            val valid = runCatching { URL(endpoint).protocol in listOf("https", "http") }.getOrDefault(false)
                            if (valid) { prefs.edit().putString("endpoint", endpoint.trim().trimEnd('/')).apply(); updateMessage = "Server saved" } else updateMessage = "Enter a valid HTTP(S) URL"
                        }) { Text("Save server") }
                    }
                    item { Text("Default: M-Lab NDT7 automatic server discovery. SpeedTester selects an available test server automatically; do not enter a regular website URL. NDT7 test measurements are shared with M-Lab and may be published with your IP address.", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    item { Text("Data usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    item { Text("A full test transfers several megabytes. Tests run only on demand; no background speed testing.", color = Muted) }
                    item { Text("Updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    item { Text("Current version " + BuildConfig.VERSION_NAME + ". Open the Updates tab to check GitHub Releases.", color = Muted) }
                    if (updateMessage.isNotBlank()) item { Text(updateMessage, color = Teal) }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text("App updates", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25)), shape = RoundedCornerShape(22.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Update, null, tint = Teal, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(12.dp)); Column { Text("SpeedTester", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge); Text("Installed version " + BuildConfig.VERSION_NAME, color = Muted, style = MaterialTheme.typography.bodySmall) } }
                                if (updateVersion.isNotBlank()) Text("Latest version: " + updateVersion, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(updateMessage, color = Muted)
                                if (updateDetails.isNotBlank()) { HorizontalDivider(color = Color(0xFF26343E)); Text("What's new", fontWeight = FontWeight.SemiBold); Text(updateDetails, color = Muted, style = MaterialTheme.typography.bodyMedium) }
                                if (updateDownloading) {
                                    LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth())
                                    val downloadedText = String.format(java.util.Locale.US, "%.2f MB", updateDownloadedBytes / 1048576.0)
                                    val totalText = if (updateTotalBytes > 0L) String.format(java.util.Locale.US, "%.2f MB", updateTotalBytes / 1048576.0) else "unknown size"
                                    Text(if (updateTotalBytes > 0L) "Downloading update: ${(updateProgress * 100).toInt()}% ($downloadedText / $totalText)" else "Downloading update: $downloadedText / $totalText", color = Teal, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = {
                                    scope.launch {
                                        updateMessage = "Checking GitHub Releases…"
                                        val result = withContext(Dispatchers.IO) { UpdateChecker.getRelease(context) }
                                        updateVersion = result.version
                                        updateDetails = result.notes
                                        updateUrl = result.apkUrl
                                        updateAvailable = result.available
                                        updateMessage = result.message
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
                                if (updateAvailable && updateUrl.isNotBlank()) Button(onClick = {
                                    if (!updateDownloading) scope.launch {
                                        updateDownloading = true
                                        updateProgress = 0f
                                        updateDownloadedBytes = 0L
                                        updateTotalBytes = -1L
                                        updateMessage = "Preparing update download…"
                                        val result = withContext(Dispatchers.IO) {
                                            UpdateChecker.downloadAndInstall(context, updateUrl) { downloaded, total ->
                                                scope.launch {
                                                    updateDownloadedBytes = downloaded
                                                    updateTotalBytes = total
                                                    if (total > 0L) updateProgress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                                    updateMessage = if (total > 0L) "Downloading update… ${(downloaded * 100 / total).coerceIn(0L, 100L)}%" else "Downloading update…"
                                                }
                                            }
                                        }
                                        updateMessage = result
                                        updateDownloading = false
                                    }
                                }, enabled = !updateDownloading, modifier = Modifier.fillMaxWidth()) { Text(if (updateDownloading) "Downloading…" else "Download and install") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = Muted, style = MaterialTheme.typography.labelSmall); Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge); Text(unit, color = Muted, style = MaterialTheme.typography.labelSmall) }
}
@Composable private fun InfoRow(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted); Text(value, fontWeight = FontWeight.Medium) } }
@Composable private fun BandwidthChart(downloadSamples: List<Float>, uploadSamples: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF0D151B), RoundedCornerShape(12.dp)).padding(8.dp)) {
        val allSamples = downloadSamples + uploadSamples
        val maxValue = max(10f, allSamples.maxOrNull() ?: 10f)
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(Color(0xFF26343E), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        fun drawSeries(samples: List<Float>, color: Color) { if (samples.size < 2) return; val path = Path(); samples.forEachIndexed { i, value -> val x = size.width * i / (samples.size - 1).toFloat(); val y = size.height - (value / maxValue).coerceIn(0f, 1f) * size.height; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)); val last = samples.last(); drawCircle(color, radius = 4.dp.toPx(), center = Offset(size.width, size.height - (last / maxValue).coerceIn(0f, 1f) * size.height)) }
        drawSeries(downloadSamples, Color(0xFF32A8FF)); drawSeries(uploadSamples, Color(0xFFFFA64D))
    }
}
@Composable private fun StatTile(label: String, value: String, unit: String, tint: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF0D151B)), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(unit, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
private fun shareTestResult(context: Context, item: TestResult) {
    val shareText = buildString {
        append("SpeedTester result · ").append(item.date).append("\n")
        append("Download: %.1f Mbps (avg %.1f, peak %.1f)\n".format(item.download, item.downloadAverage, item.downloadPeak))
        append("Upload: %.1f Mbps (avg %.1f, peak %.1f)\n".format(item.upload, item.uploadAverage, item.uploadPeak))
        append("Idle TCP latency: ").append(item.ping).append(" ms · jitter %.1f ms\n".format(item.jitter))
        append("Loaded TCP latency: ").append(if (item.loadedPing >= 0) "${item.loadedPing} ms" else "unavailable").append(" · jitter %.1f ms\n".format(item.loadedJitter))
        append("Connection probes failed: ").append(item.failedProbes).append("/").append(item.probeCount).append("\n")
        append("Server: ").append(item.server)
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), "Share speed test result"))
}
private data class NetworkInfo(val type: String, val connected: Boolean, val signal: String, val quality: String, val link: String, val frequency: String, val note: String)
@Suppress("DEPRECATION")
private fun readNetworkInfo(context: Context): NetworkInfo {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    val info = wifi.connectionInfo
    val type = when {
        caps == null -> "Disconnected"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Other"
    }
    val isWifi = type == "Wi-Fi"
    val freq = if (isWifi && info.frequency > 0) (if (info.frequency >= 5925) "6 GHz" else if (info.frequency >= 4900) "5 GHz" else "2.4 GHz") + " (" + info.frequency + " MHz)" else "—"
    val hasWifiInfo = isWifi && info.networkId != -1 && info.rssi != -127
    val rssi = if (hasWifiInfo) info.rssi else 0
    val quality = when { !hasWifiInfo -> "Unavailable"; rssi >= -50 -> "Excellent"; rssi >= -60 -> "Very good"; rssi >= -67 -> "Good"; rssi >= -75 -> "Fair"; else -> "Weak" }
    val note = when { !isWifi -> "Wi-Fi is not the active network"; !hasWifiInfo -> "Android did not expose Wi-Fi RSSI"; else -> "RSSI is an Android-reported estimate and may fluctuate" }
    return NetworkInfo(type, caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true, if (hasWifiInfo) "$rssi dBm" else "—", quality, if (isWifi && info.linkSpeed > 0) info.linkSpeed.toString() + " Mbps" else "—", freq, note)
}
private data class ServerDetails(val host: String, val city: String, val country: String, val latitude: Double? = null, val longitude: Double? = null) {
    val location: String get() = listOf(city, country).filter { it.isNotBlank() && it != "null" }.joinToString(", ")
}
private object ServerGeoLocator {
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    fun locate(city: String, country: String): Pair<Double, Double>? {
        if (city.isBlank()) return null
        val queries = listOf("$city, $country", city).filter { it.isNotBlank() }.distinct()
        for ((index, text) in queries.withIndex()) {
            if (index > 0) Thread.sleep(1100L)
            val found = runCatching {
                val query = java.net.URLEncoder.encode(text, "UTF-8")
                val request = Request.Builder()
                    .url("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=$query")
                    .header("User-Agent", "SpeedTester-Android/1.0 (server map)")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val results = org.json.JSONArray(response.body?.string() ?: "[]")
                    if (results.length() == 0) return@use null
                    val item = results.getJSONObject(0)
                    val lat = item.optString("lat").toDoubleOrNull() ?: return@use null
                    val lon = item.optString("lon").toDoubleOrNull() ?: return@use null
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) null else lat to lon
                }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }
}
@Composable private fun TcpInfoRows(info: NdtTcpInfo?) {
    if (info == null || listOf(info.minRttUs, info.rttUs, info.rttVarUs, info.bytesRetrans, info.busyTimeUs, info.receiverLimitedUs, info.senderLimitedUs).all { it == null }) {
        Text("Server did not provide TCP_INFO samples for this direction.", color = Muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    InfoRow("Server minimum RTT", info.minRttUs?.let { "%.2f ms".format(java.util.Locale.US, it / 1000.0) } ?: "—")
    InfoRow("Server smoothed RTT", info.rttUs?.let { "%.2f ms".format(java.util.Locale.US, it / 1000.0) } ?: "—")
    InfoRow("RTT variation", info.rttVarUs?.let { "%.2f ms".format(java.util.Locale.US, it / 1000.0) } ?: "—")
    InfoRow("TCP retransmitted bytes", info.bytesRetrans?.toString() ?: "—")
    InfoRow("Retransmission ratio", info.retransmissionPercent?.let { "%.3f%% (rough indicator)".format(java.util.Locale.US, it) } ?: "—")
    InfoRow("Sender busy time", info.busyTimeUs?.let { "%.1f s".format(java.util.Locale.US, it / 1_000_000.0) } ?: "—")
    InfoRow("Receiver-window limited", info.receiverLimitedUs?.let { "%.1f s".format(java.util.Locale.US, it / 1_000_000.0) } ?: "—")
    InfoRow("Sender-buffer limited", info.senderLimitedUs?.let { "%.1f s".format(java.util.Locale.US, it / 1_000_000.0) } ?: "—")
}
// Neutral pseudo-origin: tile requests must not pretend to originate from the OSM website.
private const val MAP_BASE_URL = "https://appassets.androidplatform.net/"

private fun buildServerMapHtml(context: Context, latitude: Double, longitude: Double): String {
    fun asset(name: String): String = runCatching {
        context.assets.open("leaflet/$name").bufferedReader().use { it.readText() }
    }.getOrDefault("")
    val css = asset("leaflet.css")
    val js = asset("leaflet.js")
    val script = """
        (function () {
          var msg = document.getElementById('msg');
          function fail(text) { msg.textContent = text; msg.style.display = 'block'; }
          if (typeof L === 'undefined') { fail('The map library could not be loaded.'); return; }
          var map = L.map('map').setView([$latitude, $longitude], 5);
          var loaded = 0, errors = 0;
          var tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 18,
            attribution: '&copy; OpenStreetMap contributors'
          });
          tiles.on('tileload', function () { loaded++; msg.style.display = 'none'; });
          tiles.on('tileerror', function () {
            errors++;
            if (loaded === 0 && errors >= 3) fail('Map tiles could not be loaded. Check your internet connection.');
          });
          tiles.addTo(map);
          L.circleMarker([$latitude, $longitude], {
            radius: 9, color: '#EAF2F5', weight: 2,
            fillColor: '#13B8A6', fillOpacity: 1
          }).addTo(map).bindPopup('M-Lab NDT7 test server').openPopup();
          setTimeout(function () { map.invalidateSize(); }, 300);
          setTimeout(function () {
            if (loaded === 0) fail('Map is taking too long to load. Check your internet connection and try again.');
          }, 12000);
        })();
    """.trimIndent()
    return "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
        "<style>" + css + "</style>" +
        "<style>html,body{margin:0;height:100%;background:#0d151b}#map{width:100%;height:100%;background:#0d151b}" +
        "#msg{display:block;position:absolute;left:12px;right:12px;top:50%;transform:translateY(-50%);z-index:1000;text-align:center;color:#9fb0ba;font:14px sans-serif;pointer-events:none}</style>" +
        "</head><body><div id=\"map\"></div><div id=\"msg\">Loading server map…</div><script>" + js + "</script><script>" + script + "</script></body></html>"
}

@Composable private fun ServerMapWebView(latitude: Double, longitude: Double, modifier: Modifier = Modifier) {
    val appContext = LocalContext.current
    val html = remember(latitude, longitude) { buildServerMapHtml(appContext, latitude, longitude) }
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0D151B"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.loadsImagesAutomatically = true
            settings.blockNetworkImage = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString + " SpeedTester-Android/1.0"
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = WebViewClient()
            tag = html
            loadDataWithBaseURL(MAP_BASE_URL, html, "text/html", "UTF-8", null)
        }
    }, update = { view ->
        if (view.tag != html) {
            view.tag = html
            view.loadDataWithBaseURL(MAP_BASE_URL, html, "text/html", "UTF-8", null)
        }
    })
}
private data class NdtTcpInfo(
    val minRttUs: Long? = null, val rttUs: Long? = null, val rttVarUs: Long? = null,
    val bytesRetrans: Long? = null, val bytesSent: Long? = null, val bytesAcked: Long? = null,
    val busyTimeUs: Long? = null, val receiverLimitedUs: Long? = null, val senderLimitedUs: Long? = null
) {
    val retransmissionPercent: Double? get() = if ((bytesSent ?: 0L) > 0L && bytesRetrans != null) bytesRetrans!!.toDouble() * 100.0 / bytesSent!! else null
}
private data class SpeedOutcome(val download: Double, val upload: Double, val ping: Long, val jitter: Double, val downloadAverage: Double, val downloadPeak: Double, val uploadAverage: Double, val uploadPeak: Double, val loadedPing: Long, val loadedJitter: Double, val failedProbes: Int, val probeCount: Int, val downloadTcp: NdtTcpInfo?, val uploadTcp: NdtTcpInfo?, val serverIp: String, val dnsLookupMs: Long)
private data class NdtMeasurement(val mbps: Double, val averageMbps: Double, val peakMbps: Double, val bytes: Long, val elapsedSeconds: Double, val tcpInfo: NdtTcpInfo?)
private data class QualityAssessment(val score: Int, val label: String, val detail: String)
private data class RegionalBenchmark(val downloadMedian: Double, val uploadMedian: Double, val pingMedian: Double?, val sampleCount: Long, val firstDate: String, val lastDate: String, val year: Int)
private object RegionalBenchmarkClient {
    private val client = OkHttpClient.Builder().callTimeout(18, TimeUnit.SECONDS).build()
    fun loadMauritius(): RegionalBenchmark {
        val thisYear = java.time.LocalDate.now().year
        val years = listOf(thisYear, thisYear - 1)
        for (year in years) {
            val url = "https://statistics.measurementlab.net/v0/AF/MU/$year/histogram_daily_stats.json"
            val responseData = runCatching {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "SpeedTester-Android/1.0").build()).execute().use { response ->
                    if (!response.isSuccessful) error("M-Lab Statistics API returned HTTP " + response.code)
                    org.json.JSONArray(response.body?.string() ?: "[]")
                }
            }.getOrNull() ?: continue
            val daily = linkedMapOf<String, JSONObject>()
            for (i in 0 until responseData.length()) {
                val row = responseData.optJSONObject(i) ?: continue
                val date = row.optString("date")
                if (date.isNotBlank() && row.has("download_MED") && row.has("upload_MED")) daily.putIfAbsent(date, row)
            }
            val usable = daily.entries.sortedBy { it.key }.takeLast(30).map { it.value }
            val downloads = usable.mapNotNull { it.optDouble("download_MED", Double.NaN).takeIf { value -> value.isFinite() && value > 0.0 } }
            val uploads = usable.mapNotNull { it.optDouble("upload_MED", Double.NaN).takeIf { value -> value.isFinite() && value > 0.0 } }
            val pings = usable.mapNotNull { it.optDouble("download_minRTT_MED", Double.NaN).takeIf { value -> value.isFinite() && value >= 0.0 } }
            if (downloads.isNotEmpty() && uploads.isNotEmpty()) {
                val samples = usable.sumOf { it.optLong("dl_samples_day", 0L).coerceAtLeast(0L) }
                return RegionalBenchmark(median(downloads), median(uploads), pings.takeIf { it.isNotEmpty() }?.let { median(it) }, samples, daily.keys.sorted().takeLast(30).firstOrNull().orEmpty(), daily.keys.sorted().lastOrNull().orEmpty(), year)
            }
        }
        error("M-Lab has no usable Mauritius regional statistics available right now.")
    }
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
}
private fun assessQuality(download: Double, upload: Double, ping: Long, jitter: Double, loadedPing: Long, failed: Int, probes: Int): QualityAssessment {
    if (download <= 0.0 && upload <= 0.0 && ping < 0L) return QualityAssessment(0, "Not rated", "Run a speed test to calculate a score.")
    val downScore = (download / 100.0).coerceIn(0.0, 1.0) * 35.0
    val upScore = (upload / 50.0).coerceIn(0.0, 1.0) * 20.0
    val pingScore = if (ping < 0L) 0.0 else (1.0 - ((ping - 15L).coerceAtLeast(0L) / 185.0)).coerceIn(0.0, 1.0) * 20.0
    val jitterScore = (1.0 - (jitter / 50.0)).coerceIn(0.0, 1.0) * 10.0
    val loadedScore = if (loadedPing < 0L || ping < 0L) 5.0 else (1.0 - ((loadedPing - ping).coerceAtLeast(0L) / 250.0)).coerceIn(0.0, 1.0) * 10.0
    val stabilityScore = if (probes <= 0) 5.0 else ((probes - failed).coerceAtLeast(0).toDouble() / probes) * 5.0
    val score = (downScore + upScore + pingScore + jitterScore + loadedScore + stabilityScore).toInt().coerceIn(0, 100)
    val label = when { score >= 85 -> "Excellent"; score >= 70 -> "Very good"; score >= 50 -> "Good"; score >= 30 -> "Fair"; else -> "Needs attention" }
    return QualityAssessment(score, label, "A SpeedTester estimate based on speed, latency, jitter and stability—not an official M-Lab rating.")
}
private data class NdtServerCandidate(
    val host: String, val city: String, val country: String, val countryName: String,
    val downloadUrl: String, val uploadUrl: String,
    val latitude: Double? = null, val longitude: Double? = null,
    val distanceKm: Double? = null, val latencyMs: Long? = null
) {
    val label: String get() = "M-Lab NDT7 · " + city.ifBlank { host }
}
private fun distanceKmFromMauritius(lat: Double?, lon: Double?): Double? {
    if (lat == null || lon == null) return null
    val originLat = Math.toRadians(-20.2); val originLon = Math.toRadians(57.5)
    val targetLat = Math.toRadians(lat); val targetLon = Math.toRadians(lon)
    val dLat = targetLat - originLat; val dLon = targetLon - originLon
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) + kotlin.math.cos(originLat) * kotlin.math.cos(targetLat) * kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 6371.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}
private object SpeedEngine {
    private const val LOCATE_URL = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7?client_name=SpeedTester&client_version=1.0"
    private const val PROTOCOL = "net.measurementlab.ndt.v7"
    private const val TEST_SECONDS = 10.0
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(20, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()

    suspend fun discoverCandidates(): List<NdtServerCandidate> = withContext(Dispatchers.IO) {
        val urls = listOf(LOCATE_URL + "&country=MU&strict=true", LOCATE_URL + "&country=MU", LOCATE_URL)
        val found = linkedMapOf<String, NdtServerCandidate>()
        for (url in urls) {
            try {
                val json = client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "SpeedTester-Android/1.0").build()).execute().use { response ->
                    if (!response.isSuccessful) return@use JSONObject()
                    JSONObject(response.body?.string() ?: "{}")
                }
                val results = json.optJSONArray("results") ?: continue
                for (i in 0 until results.length()) {
                    val result = results.optJSONObject(i) ?: continue
                    val urlsObject = result.optJSONObject("urls") ?: continue
                    var down = ""; var up = ""
                    val keys = urlsObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.startsWith("wss://") && key.contains("ndt/v7/download")) down = urlsObject.optString(key)
                        if (key.startsWith("wss://") && key.contains("ndt/v7/upload")) up = urlsObject.optString(key)
                    }
                    if (!down.startsWith("wss://") || !up.startsWith("wss://")) continue
                    val host = runCatching { java.net.URI(down).host }.getOrNull().orEmpty()
                    if (host.isBlank() || found.containsKey(host)) continue
                    val location = result.optJSONObject("location")
                    val city = location?.optString("city").orEmpty().takeUnless { it == "null" }.orEmpty()
                    val country = location?.optString("country").orEmpty().takeUnless { it == "null" }.orEmpty()
                    val countryName = when (country.uppercase(java.util.Locale.ROOT)) { "MU" -> "Mauritius"; "MG" -> "Madagascar"; "ZA" -> "South Africa"; "KE" -> "Kenya"; "RE" -> "Réunion"; "SC" -> "Seychelles"; else -> country }
                    val lat = location?.optString("latitude")?.toDoubleOrNull()
                    val lon = location?.optString("longitude")?.toDoubleOrNull()
                    val portValue = runCatching { java.net.URI(down).port }.getOrDefault(-1)
                    val port = if (portValue > 0) portValue else 443
                    val latency = runCatching {
                        val started = System.nanoTime()
                        Socket().use { it.connect(java.net.InetSocketAddress(host, port), 900) }
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    }.getOrNull()
                    found[host] = NdtServerCandidate(host, city, country, countryName, down, up, lat, lon, distanceKmFromMauritius(lat, lon), latency)
                    if (found.size >= 10) break
                }
            } catch (_: Exception) { /* Try the next discovery scope. */ }
            if (found.size >= 10) break
        }
        found.values.sortedWith(compareBy<NdtServerCandidate> { if (it.country.equals("MU", true)) 0 else 1 }.thenBy { it.distanceKm ?: Double.MAX_VALUE }.thenBy { it.latencyMs ?: Long.MAX_VALUE }).take(10)
    }

    suspend fun run(base: String, selectedServer: NdtServerCandidate?, onServer: (ServerDetails) -> Unit, onUpdate: (String, Float, Float) -> Unit, onMetrics: (Long, Double) -> Unit, onLog: (String) -> Unit): SpeedOutcome = withContext(Dispatchers.IO) {
        var downUrl = selectedServer?.downloadUrl.orEmpty()
        var upUrl = selectedServer?.uploadUrl.orEmpty()
        var locationName = selectedServer?.let { listOf(it.city, it.countryName).filter { part -> part.isNotBlank() }.joinToString(", ") }.orEmpty().ifBlank { "location unavailable" }
        var locationCity = selectedServer?.city.orEmpty()
        var locationCountry = selectedServer?.country.orEmpty()
        if (selectedServer == null) {
            val locateUrl = if (base.contains("locate.measurementlab.net", true)) base else LOCATE_URL
            onLog("Engine: M-Lab NDT7 over secure WebSockets")
            onLog("Discovery URL: " + locateUrl)
            onLog("NDT7 subprotocol: " + PROTOCOL)
            val discoveryStartedAt = System.nanoTime()
            val json = client.newCall(Request.Builder().url(locateUrl).header("Accept", "application/json").header("User-Agent", "SpeedTester-Android/1.0").build()).execute().use { response ->
                onLog("DISCOVERY HTTP " + response.code + " " + response.message)
                if (!response.isSuccessful) error("M-Lab discovery failed: HTTP " + response.code)
                JSONObject(response.body?.string() ?: error("M-Lab returned an empty response"))
            }
            val discoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - discoveryStartedAt)
            onLog("DISCOVERY TIME: " + discoveryMs + "ms")
            val results = json.optJSONArray("results") ?: error("M-Lab discovery returned no server results")
            for (i in 0 until results.length()) {
                val result = results.optJSONObject(i) ?: continue
                val urls = result.optJSONObject("urls") ?: continue
                var d = ""; var u = ""
                val keys = urls.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.startsWith("wss://") && key.contains("ndt/v7/download")) d = urls.optString(key)
                    if (key.startsWith("wss://") && key.contains("ndt/v7/upload")) u = urls.optString(key)
                }
                if (d.startsWith("wss://") && u.startsWith("wss://")) {
                    downUrl = d; upUrl = u
                    val loc = result.optJSONObject("location")
                    locationCity = loc?.optString("city").orEmpty().takeUnless { it == "null" }.orEmpty()
                    locationCountry = loc?.optString("country").orEmpty().takeUnless { it == "null" }.orEmpty()
                    locationName = listOf(locationCity, locationCountry).filter { it.isNotBlank() }.joinToString(", ").ifBlank { locationName }
                    break
                }
            }
        } else {
            onLog("Engine: M-Lab NDT7 using the server selected in the Mauritius selector")
            onLog("Selected server endpoint: " + selectedServer.host)
            onLog("Selected server latency: " + (selectedServer.latencyMs?.let { "$it ms" } ?: "not measured"))
        }
        if (downUrl.isBlank() || upUrl.isBlank()) error("No usable secure NDT7 server was selected or discovered")
        onLog("DISCOVERY STATUS: success")
        val uri = java.net.URI(downUrl)
        val host = uri.host ?: error("M-Lab returned an invalid server URL")
        val port = if (uri.port > 0) uri.port else 443
        onLog("Selected server: " + host + " (" + locationName + ")")
        onServer(ServerDetails(host, locationCity, locationCountry))
        onLog("Download URL: " + downUrl)
        onLog("Upload URL: " + upUrl)
        onLog("Download and upload each run for up to 10 seconds")

        val dnsStart = System.nanoTime()
        val resolvedAddress = runCatching { InetAddress.getAllByName(host).firstOrNull()?.hostAddress.orEmpty() }.getOrDefault("")
        val dnsLookupMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dnsStart)
        onLog("DNS lookup: " + (resolvedAddress.ifBlank { "failed" }) + " in " + dnsLookupMs + "ms")
        val pings = mutableListOf<Long>()
        repeat(10) { index ->
            val started = System.nanoTime()
            try {
                Socket().use { it.connect(java.net.InetSocketAddress(host, port), 3000) }
                val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                pings.add(ms)
                onLog("TCP probe " + (index + 1) + "/10 OK: " + ms + "ms")
            } catch (e: Exception) {
                onLog("TCP probe " + (index + 1) + "/10 FAILED: " + e.javaClass.simpleName + ": " + e.message)
                throw e
            }
            onMetrics(pings.average().toLong(), if (pings.size > 1) pings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average() else 0.0)
            onUpdate("Measuring latency", .02f + .06f * pings.size / 10f, 0f)
            kotlinx.coroutines.delay(180)
        }
        val ping = pings.average().toLong()
        val jitter = pings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average()
        onUpdate("Downloading · M-Lab NDT7", .08f, 0f)
        val down = kotlinx.coroutines.withTimeout(15_000L) {
            runNdtWebSocket(downUrl, false) { fraction, mbps -> onUpdate("Downloading · M-Lab NDT7", (.08f + .42f * fraction).coerceIn(.08f, .50f), mbps) }
        }
        onLog("DOWNLOAD: bytes=" + down.bytes + ", elapsed=" + "%.2f".format(java.util.Locale.US, down.elapsedSeconds) + "s, average=" + "%.2f".format(java.util.Locale.US, down.mbps) + " Mbps, peak=" + "%.2f".format(java.util.Locale.US, down.peakMbps) + " Mbps")
        down.tcpInfo?.let { onLog("DOWNLOAD TCP_INFO: minRTT=" + (it.minRttUs?.let { v -> "%.2f".format(java.util.Locale.US, v / 1000.0) } ?: "n/a") + "ms RTT=" + (it.rttUs?.let { v -> "%.2f".format(java.util.Locale.US, v / 1000.0) } ?: "n/a") + "ms retrans=" + (it.retransmissionPercent?.let { v -> "%.3f".format(java.util.Locale.US, v) + "%" } ?: "n/a")) }
        onUpdate("Uploading · M-Lab NDT7", .55f, 0f)
        val up = kotlinx.coroutines.withTimeout(15_000L) {
            runNdtWebSocket(upUrl, true) { fraction, mbps -> onUpdate("Uploading · M-Lab NDT7", (.55f + .40f * fraction).coerceIn(.55f, .95f), mbps) }
        }
        onLog("UPLOAD: bytes=" + up.bytes + ", elapsed=" + "%.2f".format(java.util.Locale.US, up.elapsedSeconds) + "s, average=" + "%.2f".format(java.util.Locale.US, up.mbps) + " Mbps, peak=" + "%.2f".format(java.util.Locale.US, up.peakMbps) + " Mbps")
        up.tcpInfo?.let { onLog("UPLOAD TCP_INFO: minRTT=" + (it.minRttUs?.let { v -> "%.2f".format(java.util.Locale.US, v / 1000.0) } ?: "n/a") + "ms RTT=" + (it.rttUs?.let { v -> "%.2f".format(java.util.Locale.US, v / 1000.0) } ?: "n/a") + "ms retrans=" + (it.retransmissionPercent?.let { v -> "%.3f".format(java.util.Locale.US, v) + "%" } ?: "n/a")) }
        onUpdate("Checking connection stability", .96f, 0f)
        val loaded = mutableListOf<Long>()
        var failed = 0
        repeat(5) {
            try {
                val t = System.nanoTime()
                Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1500) }
                loaded.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t))
            } catch (_: Exception) { failed++ }
            kotlinx.coroutines.delay(100)
        }
        onLog("STABILITY probes: successful=" + loaded.size + "/5 failed=" + failed)
        val loadedPing = if (loaded.isNotEmpty()) loaded.average().toLong() else -1L
        val loadedJitter = if (loaded.size > 1) loaded.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average() else 0.0
        onLog("NDT7 server: " + host + " (" + locationName + ")")
        onUpdate("Complete", 1f, 0f)
        SpeedOutcome(down.mbps, up.mbps, ping, jitter, down.averageMbps, down.peakMbps, up.averageMbps, up.peakMbps, loadedPing, loadedJitter, failed, 5, down.tcpInfo, up.tcpInfo, resolvedAddress, dnsLookupMs)
    }

    private suspend fun runNdtWebSocket(url: String, isUpload: Boolean, onProgress: (Float, Float) -> Unit): NdtMeasurement =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                val bytes = java.util.concurrent.atomic.AtomicLong(0L)
                val startedAt = java.util.concurrent.atomic.AtomicLong(0L)
                val lastAt = java.util.concurrent.atomic.AtomicLong(0L)
                val lastBytes = java.util.concurrent.atomic.AtomicLong(0L)
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                val rates = java.util.Collections.synchronizedList(mutableListOf<Double>())
                val socketRef = java.util.concurrent.atomic.AtomicReference<okhttp3.WebSocket?>(null)
                val latestTcpInfo = java.util.concurrent.atomic.AtomicReference<NdtTcpInfo?>(null)
                fun optionalLong(json: JSONObject, key: String): Long? = if (json.has(key) && !json.isNull(key)) json.optLong(key).takeIf { it >= 0L } else null
                fun parseServerMeasurement(text: String) {
                    runCatching {
                        val tcp = JSONObject(text).optJSONObject("TCPInfo") ?: return@runCatching
                        latestTcpInfo.set(NdtTcpInfo(
                            minRttUs = optionalLong(tcp, "MinRTT"), rttUs = optionalLong(tcp, "RTT"), rttVarUs = optionalLong(tcp, "RTTVar"),
                            bytesRetrans = optionalLong(tcp, "BytesRetrans"), bytesSent = optionalLong(tcp, "BytesSent"), bytesAcked = optionalLong(tcp, "BytesAcked"),
                            busyTimeUs = optionalLong(tcp, "BusyTime"), receiverLimitedUs = optionalLong(tcp, "RWndLimited"), senderLimitedUs = optionalLong(tcp, "SndBufLimited")
                        ))
                    }
                }

                fun finish(problem: Throwable? = null) {
                    if (!finished.compareAndSet(false, true)) return
                    val start = startedAt.get()
                    val seconds = if (start > 0L) (System.nanoTime() - start).coerceAtLeast(1L) / 1_000_000_000.0 else 0.0
                    val pending = if (isUpload) socketRef.get()?.queueSize() ?: 0L else 0L
                    val transferred = (bytes.get() - pending).coerceAtLeast(0L)
                    if (problem != null) {
                        if (continuation.isActive) continuation.resumeWithException(problem)
                    } else if (transferred <= 0L || seconds <= 0.0) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("NDT7 " + (if (isUpload) "upload" else "download") + " transferred no data"))
                    } else {
                        val finalRate = transferred * 8.0 / seconds / 1_000_000.0
                        val samples = synchronized(rates) { rates.toList() }
                        val average = if (samples.isEmpty()) finalRate else samples.average()
                        val peak = samples.maxOrNull() ?: finalRate
                        if (continuation.isActive) continuation.resume(NdtMeasurement(finalRate, average, peak, transferred, seconds, latestTcpInfo.get()))
                    }
                }

                fun report() {
                    val start = startedAt.get()
                    if (start <= 0L || finished.get()) return
                    val now = System.nanoTime()
                    val oldTime = lastAt.get()
                    if (now - oldTime < 200_000_000L) return
                    val current = bytes.get()
                    val elapsed = (now - oldTime).coerceAtLeast(1L) / 1_000_000_000.0
                    val rate = (current - lastBytes.get()).coerceAtLeast(0L) * 8.0 / elapsed / 1_000_000.0
                    if (rate > 0.0) rates.add(rate)
                    lastAt.set(now)
                    lastBytes.set(current)
                    onProgress(((now - start).toDouble() / 1_000_000_000.0 / TEST_SECONDS).toFloat().coerceIn(0f, 1f), rate.toFloat())
                }

                val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", PROTOCOL).header("User-Agent", "SpeedTester-Android/1.0").build()
                val listener = object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                        val openedAt = System.nanoTime()
                        startedAt.set(openedAt); lastAt.set(openedAt); lastBytes.set(0L)
                        if (isUpload) {
                            client.dispatcher.executorService.execute {
                                val payload = ByteArray(16 * 1024).also { java.security.SecureRandom().nextBytes(it) }
                                val frame = okio.ByteString.of(*payload)
                                while (!finished.get() && System.nanoTime() - openedAt < TimeUnit.SECONDS.toNanos(10)) {
                                    if (webSocket.queueSize() < 1_000_000L) {
                                        if (webSocket.send(frame)) bytes.addAndGet(frame.size.toLong()) else break
                                    } else {
                                        try { Thread.sleep(2L) } catch (_: InterruptedException) { break }
                                    }
                                    report()
                                }
                                if (!finished.get()) {
                                    webSocket.close(1000, "NDT7 upload complete")
                                    finish()
                                }
                            }
                        }
                    }
                    override fun onMessage(webSocket: okhttp3.WebSocket, data: okio.ByteString) {
                        if (!isUpload) { bytes.addAndGet(data.size.toLong()); report() }
                    }
                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        parseServerMeasurement(text)
                    }
                    override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        finish(if (code == 1000) null else IllegalStateException("NDT7 server closed with code " + code + ": " + reason))
                    }
                    override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                        finish(if (code == 1000) null else IllegalStateException("NDT7 server closed with code " + code + ": " + reason))
                    }
                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                        val detail = if (response != null) "HTTP " + response.code + " " + response.message else (t.message ?: t.javaClass.simpleName)
                        finish(IllegalStateException("NDT7 " + (if (isUpload) "upload" else "download") + " WebSocket failed: " + detail, t))
                    }
                }
                socketRef.set(client.newWebSocket(request, listener))
                continuation.invokeOnCancellation { socketRef.get()?.cancel() }
            }
        }
}
private data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val available: Boolean, val message: String)
private object UpdateChecker {
    private const val API = "https://api.github.com/repos/N2K02/SpeedTester/releases/latest"
    fun getRelease(context: Context): ReleaseInfo = try {
        val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(API).header("Accept", "application/vnd.github+json").header("User-Agent", "SpeedTester-Android").build()
        val json = client.newCall(request).execute().use { r -> check(r.isSuccessful) { "GitHub returned HTTP " + r.code }; JSONObject(r.body?.string() ?: "{}") }
        val tag = json.optString("tag_name").removePrefix("v")
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        val notes = json.optString("body").ifBlank { "No release notes were provided." }
        var apkUrl = ""
        val assets = json.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) { val asset = assets.getJSONObject(i); if (asset.optString("name").endsWith(".apk", true)) { apkUrl = asset.optString("browser_download_url"); break } }
        val available = compareVersions(tag, current) > 0 && apkUrl.isNotBlank()
        val message = when {
            compareVersions(tag, current) <= 0 -> "You're up to date."
            apkUrl.isBlank() -> "A newer release exists, but no APK is attached yet."
            else -> "A new version is available."
        }
        ReleaseInfo(tag, notes, apkUrl, available, message)
    } catch (e: Exception) { ReleaseInfo("", "", "", false, "Update check failed: " + (e.message ?: "network error")) }
    fun downloadAndInstall(context: Context, apkUrl: String, onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit): String {
        return try {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Allow installs from SpeedTester, then tap Download and install again."
        }
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
        val response = client.newCall(Request.Builder().url(apkUrl).header("User-Agent", "SpeedTester-Android").build()).execute()
        val apkFile = File(context.cacheDir, "updates/SpeedTester-update.apk")
        apkFile.parentFile?.mkdirs()
        response.use { r ->
            if (!r.isSuccessful) error("APK download failed: HTTP ${r.code}")
            val body = r.body ?: error("GitHub returned an empty APK")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                apkFile.outputStream().buffered().use { output ->
                    onProgress(0L, totalBytes)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
            if (totalBytes > 0L && downloadedBytes != totalBytes) error("APK download incomplete: received $downloadedBytes of $totalBytes bytes")
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apkFile)
        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(install)
        "APK downloaded. Android's installer is ready."
        } catch (e: Exception) { "Update failed: " + (e.message ?: "network error") }
    }
    private fun compareVersions(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }; val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until max(x.size, y.size)) { val d = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0); if (d != 0) return d }
        return 0
    }
}
