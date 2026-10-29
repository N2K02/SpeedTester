package com.speedtester.app

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.content.ClipboardManager
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import dev.n2k02.theme.N2K02Theme
import androidx.compose.foundation.clickable
import dev.n2k02.updater.hasPendingUpdate
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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

data class TestResult(val date: String, val download: Double, val upload: Double, val ping: Long, val jitter: Double, val server: String, val downloadAverage: Double = 0.0, val downloadPeak: Double = 0.0, val uploadAverage: Double = 0.0, val uploadPeak: Double = 0.0, val loadedPing: Long = -1L, val loadedJitter: Double = 0.0, val failedProbes: Int = 0, val probeCount: Int = 0, val networkType: String = "Unknown", val internetAvailable: Boolean = false, val wifiSignal: String = "—", val wifiQuality: String = "—", val wifiLink: String = "—", val wifiFrequency: String = "—", val connectionNote: String = "", val discoveryStatus: String = "Not recorded", val discoveryMs: Long = -1L, val wifiSsid: String = "Unavailable", val wifiBssid: String = "Unavailable")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); LiveUpdater.init(this); AppLog.init(applicationContext); setContent { SpeedTesterApp(this) } }
    override fun onResume() { super.onResume(); LiveUpdater.onResume(this) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedTesterApp(context: Context) {
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("speedtester", Context.MODE_PRIVATE) }
    val endpoint = "https://www.speedtest.net/api/js/servers?engine=js&https_functional=true&limit=1000&search=Mauritius"
    var availableServers by remember { mutableStateOf<List<NdtServerCandidate>>(emptyList()) }
    var selectedServer by remember { mutableStateOf<NdtServerCandidate?>(null) }
    var serversLoading by remember { mutableStateOf(false) }
    var serversError by remember { mutableStateOf("") }
    var serverDiscoveryMs by remember { mutableLongStateOf(-1L) }
    var serverDiscoveryStatus by remember { mutableStateOf("Not started") }
    var serverSearch by remember { mutableStateOf("") }
    var serverPage by remember { mutableIntStateOf(0) }
    var distanceOrigin by remember { mutableStateOf(-20.2 to 57.5) }
    var userLocated by remember { mutableStateOf(false) }
    val onDeviceLocation: (Location) -> Unit = { location ->
        distanceOrigin = location.latitude to location.longitude
        userLocated = true
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestDeviceLocation(context, onDeviceLocation)
    }
    // If permission was granted earlier, start locating straight away so the map can open on the user.
    LaunchedEffect(Unit) { requestDeviceLocation(context, onDeviceLocation) }
    LaunchedEffect(Unit) {
        serversLoading = true
        serversError = ""
        serverDiscoveryStatus = "Discovering Mauritius servers…"
        val discoveryStarted = System.nanoTime()
        try {
            availableServers = withContext(Dispatchers.IO) { SpeedEngine.discoverCandidates() }
            serverDiscoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - discoveryStarted)
            if (availableServers.isEmpty()) {
                serversError = "No HTTPS-compatible Ookla test servers were found in Mauritius. Try Refresh; only country code MU is shown."
                serverDiscoveryStatus = "No compatible servers found"
            } else serverDiscoveryStatus = "Complete · ${availableServers.size} Mauritius servers"
        } catch (e: Exception) {
            serversError = e.message ?: "Unable to discover compatible servers"
            serverDiscoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - discoveryStarted)
            serverDiscoveryStatus = "Failed"
        } finally { serversLoading = false }
    }
    val serversWithDistance = remember(availableServers, distanceOrigin) {
        availableServers.map { candidate ->
            val km = distanceKmBetween(distanceOrigin.first, distanceOrigin.second, candidate.latitude, candidate.longitude)
            candidate.copy(distanceKm = km)
        }
    }
    val filteredServers = remember(serversWithDistance, serverSearch) {
        serversWithDistance.filter { candidate ->
            candidate.country.equals("MU", true) &&
            (serverSearch.isBlank() || listOf(candidate.host, candidate.city, candidate.countryName, candidate.label).any { it.contains(serverSearch.trim(), true) })
        }.sortedWith(compareBy<NdtServerCandidate> { it.distanceKm ?: Double.MAX_VALUE }.thenBy { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.host })
    }
    val serverPageSize = 3
    val serverPageCount = maxOf(1, (filteredServers.size + serverPageSize - 1) / serverPageSize)
    val visibleServers = filteredServers.drop(serverPage * serverPageSize).take(serverPageSize)
    LaunchedEffect(filteredServers.size, serverSearch) { serverPage = 0 }
    // No server to pick by hand each launch: reuse last time's server if it's still listed,
    // otherwise take the one with the lowest latency (the list is sorted that way).
    LaunchedEffect(availableServers) {
        if (selectedServer == null && availableServers.isNotEmpty()) {
            val savedId = prefs.getString("selected_server_id", null)
            selectedServer = availableServers.firstOrNull { it.serverId.isNotBlank() && it.serverId == savedId }
                ?: availableServers.first()
        }
    }
    LaunchedEffect(selectedServer?.serverId) {
        selectedServer?.serverId?.takeIf { it.isNotBlank() }?.let { prefs.edit().putString("selected_server_id", it).apply() }
    }
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
    var showUpdateDetails by remember { mutableStateOf(false) }
    val updateStatus by LiveUpdater.controller.status.collectAsState()
    val activityLog = remember { mutableStateListOf("Ready. Start a test to see live diagnostics here.") }
    var netInfo by remember { mutableStateOf(readNetworkInfo(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            netInfo = readNetworkInfo(context)
            kotlinx.coroutines.delay(2500)
        }
    }
    var serverDetails by remember { mutableStateOf(ServerDetails(prefs.getString("server_host", "").orEmpty(), prefs.getString("server_city", "").orEmpty(), prefs.getString("server_country", "").orEmpty(), prefs.getString("server_lat", null)?.toDoubleOrNull(), prefs.getString("server_lon", null)?.toDoubleOrNull())) }
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
    var loggedPhase by remember { mutableStateOf("") }
    var gaugeUpload by remember { mutableStateOf(false) }
    var gaugeRunId by remember { mutableIntStateOf(0) }
    var liveDownloadMbps by remember { mutableFloatStateOf(0f) }
    var liveUploadMbps by remember { mutableFloatStateOf(0f) }
    var showExtendedMetrics by remember { mutableStateOf(false) }
    var showServerPicker by remember { mutableStateOf(false) }
    var livePing by remember { mutableLongStateOf(-1L) }
    var liveJitter by remember { mutableDoubleStateOf(0.0) }
    // All detailed test diagnostics now use the standard opt-in persistent Logs page.
    fun logDiagnostic(message: String) { AppLog.add("SPEED", message) }
    val downloadSamples = remember { mutableStateListOf<Float>() }
    val uploadSamples = remember { mutableStateListOf<Float>() }
    val history = remember {
        runCatching {
            val arr = org.json.JSONArray(TestHistoryStore.read(context, prefs.getString("history", "[]")))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TestResult(o.optString("date"), o.optDouble("download"), o.optDouble("upload"), o.optLong("ping", -1), o.optDouble("jitter"), o.optString("server"), o.optDouble("downloadAverage", 0.0).finiteOrZero(), o.optDouble("downloadPeak", 0.0).finiteOrZero(), o.optDouble("uploadAverage", 0.0).finiteOrZero(), o.optDouble("uploadPeak", 0.0).finiteOrZero(), o.optLong("loadedPing", -1), o.optDouble("loadedJitter", 0.0).finiteOrZero(), o.optInt("failedProbes"), o.optInt("probeCount"), o.optString("networkType", "Unknown"), o.optBoolean("internetAvailable", false), o.optString("wifiSignal", "—"), o.optString("wifiQuality", "—"), o.optString("wifiLink", "—"), o.optString("wifiFrequency", "—"), o.optString("connectionNote", ""), o.optString("discoveryStatus", "Not recorded"), o.optLong("discoveryMs", -1), o.optString("wifiSsid", "Unavailable"), o.optString("wifiBssid", "Unavailable"))
            }.toMutableStateList()
        }.getOrElse { mutableStateListOf() }
    }
    var selectedHistory by remember { mutableStateOf<TestResult?>(null) }
    BackHandler(enabled = activeTab == 8) { selectedHistory = null; activeTab = 1 }
    BackHandler(enabled = activeTab == 6 || activeTab == 7) { activeTab = 0 }
    BackHandler(enabled = activeTab == 4 || activeTab == 5) { activeTab = 3 }
    BackHandler(enabled = activeTab == 3 && showUpdateDetails) { showUpdateDetails = false }
    BackHandler(enabled = activeTab == 1 || activeTab == 2) { activeTab = 3 }
    N2K02Theme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                when {
                                    activeTab == 8 -> Icons.Default.History
                                    activeTab == 7 -> Icons.Default.NetworkCheck
                                    activeTab == 6 -> Icons.Default.Bolt
                                    activeTab == 5 -> Icons.Default.Wifi
                                    activeTab == 4 -> Icons.Rounded.Article
                                    activeTab == 3 && showUpdateDetails -> Icons.Default.Update
                                    activeTab == 3 -> Icons.Default.Settings
                                    activeTab == 2 -> Icons.Default.Timeline
                                    activeTab == 1 -> Icons.Default.History
                                    else -> Icons.Default.NetworkCheck
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(when {
                                activeTab == 8 -> "Test result"
                                activeTab == 7 -> "Network Info"
                                activeTab == 6 -> "Test Servers"
                                activeTab == 5 -> "Wi-Fi Analyser"
                                activeTab == 4 -> "Logs"
                                activeTab == 3 && showUpdateDetails -> "About & updates"
                                activeTab == 3 -> "Settings"
                                activeTab == 2 -> "Server"
                                activeTab == 1 -> "History"
                                else -> "Home"
                            }, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    actions = {
                        dev.n2k02.updater.N2K02AppIcon(Modifier.size(28.dp))
                        Text("SpeedTester", modifier = Modifier.padding(start = 6.dp, end = 12.dp),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0; showUpdateDetails = false },
                        icon = { Icon(Icons.Default.NetworkCheck, null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = activeTab != 0,
                        onClick = { activeTab = 3; showUpdateDetails = false },
                        icon = { dev.n2k02.updater.N2K02UpdateBadgedIcon(updateStatus) { Icon(Icons.Default.Settings, "Settings") } },
                        label = { Text("Settings") },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = {
                            // Preserve an explicit cancellation event before stopping work.
                            if (testing) logDiagnostic("SPEED_TEST_CANCELLED_BY_QUIT")
                            scope.cancel()
                            LiveUpdater.controller.cancel()
                            (context as? ComponentActivity)?.finishAndRemoveTask()
                        },
                        icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = "Quit") },
                        label = { Text("Quit") },
                        alwaysShowLabel = true,
                    )
                }
            }, containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            when (activeTab) {
                5 -> WifiAnalyzerScreen(context, modifier = Modifier.fillMaxSize().padding(padding))
                4 -> SpeedTesterLogsPage(
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
                0 -> LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
                ) {
                    item {
                        Card(onClick = { activeTab = 6 },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxWidth().padding(15.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Test Server", style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(selectedServer?.let { it.sponsor.ifBlank { "Ookla" } + " · " + it.city }
                                            ?: "Finding Mauritius server…",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold, maxLines = 2)
                                        Text("Mauritius · #" + (selectedServer?.serverId ?: "—"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text("Change ›", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(selectedServer?.distanceKm?.let { "%.1f km".format(java.util.Locale.US, it) }
                                        ?: "Distance —", style = MaterialTheme.typography.bodySmall)
                                    Text(selectedServer?.latencyMs?.let { it.toString() + " ms TCP" }
                                        ?: "Ping —", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text("HTTPS", style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Wifi, null, tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(19.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(if (netInfo.type == "Wi-Fi") "Wi-Fi · " + netInfo.ssid else netInfo.type,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        if (netInfo.type == "Wi-Fi") Text("BSSID: " + netInfo.bssid,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelMedium)
                                val gaugeSpeed = if (testing) {
                                    if (gaugeUpload) liveUploadMbps else liveDownloadMbps
                                } else if (gaugeUpload) (history.firstOrNull()?.upload ?: upload).toFloat()
                                else (history.firstOrNull()?.download ?: download).toFloat()
                                SpeedometerDial(gaugeSpeed, gaugeUpload, gaugeRunId)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Ping", style = MaterialTheme.typography.labelSmall)
                                        val shownPing = if (testing && livePing >= 0) livePing
                                            else if (ping >= 0) ping else history.firstOrNull()?.ping ?: -1
                                        Text(if (shownPing >= 0) shownPing.toString() + " ms" else "—",
                                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Jitter", style = MaterialTheme.typography.labelSmall)
                                        Text("%.1f ms".format(java.util.Locale.US,
                                            if (testing) liveJitter else history.firstOrNull()?.jitter ?: jitter),
                                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Failed probes", style = MaterialTheme.typography.labelSmall)
                                        Text(if (probeCount > 0) failedProbes.toString() + "/" + probeCount
                                            else history.firstOrNull()?.let {
                                                it.failedProbes.toString() + "/" + it.probeCount
                                            } ?: "—", style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold)
                                    }
                                }
                                Button(onClick = {
                                    if (!testing) {
                                        gaugeRunId++; gaugeUpload = false; liveDownloadMbps = 0f; liveUploadMbps = 0f; testing = true; error = null; progress = 0f; loggedPhase = ""; livePing = -1L; liveJitter = 0.0; downloadAverage = 0.0; downloadPeak = 0.0; uploadAverage = 0.0; uploadPeak = 0.0; loadedPing = -1L; loadedJitter = 0.0; failedProbes = 0; probeCount = 0; downloadSamples.clear(); uploadSamples.clear(); status = "Preparing test…"; activityLog.clear(); activityLog.add("Validating Mauritius Ookla server"); activityLog.add("Preparing HTTPS throughput and latency probes"); activityLog.add("Directory: " + endpoint.trim()); while (activityLog.size > 8) activityLog.removeAt(0)
                                                            logDiagnostic("========== SPEED TEST START ==========")
                                        logDiagnostic("Server directory: Speedtest.net public API · country MU")
                                        logDiagnostic("Network: " + netInfo.type + " | internet=" + netInfo.connected + " | Wi-Fi RSSI=" + netInfo.signal + " | quality=" + netInfo.quality + " | link=" + netInfo.link + " | band=" + netInfo.frequency)
                                        logDiagnostic("Device clock: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", java.util.Locale.getDefault()).format(java.util.Date()))
                                        val testNetworkAtStart = readNetworkInfo(context)
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
                                                    if (phase.contains("Upload", true)) {
                                                        gaugeUpload = true
                                                        liveUploadMbps = mbps.coerceAtLeast(0f)
                                                    } else if (phase.contains("Download", true)) {
                                                        gaugeUpload = false
                                                        liveDownloadMbps = mbps.coerceAtLeast(0f)
                                                    }
                                                    if (loggedPhase != phase) { loggedPhase = phase; activityLog.add(phase + " phase started"); while (activityLog.size > 8) activityLog.removeAt(0) }
                                                    if (mbps > 0f) { val isUpload = phase.contains("Upload", true); val target = if (isUpload) uploadSamples else downloadSamples; target.add(mbps); if (target.size > 80) target.removeAt(0); if (isUpload) { uploadAverage = target.average(); uploadPeak = (target.maxOrNull() ?: mbps).toDouble() } else { downloadAverage = target.average(); downloadPeak = (target.maxOrNull() ?: mbps).toDouble() }; val line = phase + " live rate: " + String.format(java.util.Locale.US, "%.1f Mbps", mbps); if (activityLog.lastOrNull()?.startsWith(phase + " live rate:") == true) activityLog[activityLog.lastIndex] = line else { activityLog.add(line); if (activityLog.size > 8) activityLog.removeAt(0) } }
                                                }, { p, j -> ping = p; jitter = j; livePing = p; liveJitter = j }, { message -> scope.launch { logDiagnostic(message); when { message.startsWith("DISCOVERY TIME:") -> serverDiscoveryMs = message.substringAfter(":").trim().removeSuffix("ms").toLongOrNull() ?: -1L; message.startsWith("DISCOVERY STATUS:") -> serverDiscoveryStatus = message.substringAfter(":").trim() } } })
                                                download = result.download; upload = result.upload; ping = result.ping; jitter = result.jitter
                                                downloadTcp = result.downloadTcp; uploadTcp = result.uploadTcp
                                                serverIp = result.serverIp; dnsLookupMs = result.dnsLookupMs
                                                downloadAverage = result.downloadAverage; downloadPeak = result.downloadPeak; uploadAverage = result.uploadAverage; uploadPeak = result.uploadPeak
                                                loadedPing = result.loadedPing; loadedJitter = result.loadedJitter; failedProbes = result.failedProbes; probeCount = result.probeCount
                                                val connectionSnapshot = readNetworkInfo(context)
                                                val saved = TestResult(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), download, upload, ping, jitter, listOf(serverDetails.host, serverDetails.location).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { endpoint }, downloadAverage, downloadPeak, uploadAverage, uploadPeak, loadedPing, loadedJitter, failedProbes, probeCount, connectionSnapshot.type, connectionSnapshot.connected, connectionSnapshot.signal, connectionSnapshot.quality, connectionSnapshot.link, connectionSnapshot.frequency, connectionSnapshot.note, serverDiscoveryStatus, serverDiscoveryMs, testNetworkAtStart.ssid, testNetworkAtStart.bssid)
                                                history.add(0, saved); while (history.size > 100) history.removeAt(history.lastIndex)
                                                val arr = org.json.JSONArray()
                                                history.forEach { x -> arr.put(JSONObject().put("date", x.date).put("download", x.download.finiteOrZero()).put("upload", x.upload.finiteOrZero()).put("ping", x.ping).put("jitter", x.jitter.finiteOrZero()).put("server", x.server).put("downloadAverage", x.downloadAverage.finiteOrZero()).put("downloadPeak", x.downloadPeak.finiteOrZero()).put("uploadAverage", x.uploadAverage.finiteOrZero()).put("uploadPeak", x.uploadPeak.finiteOrZero()).put("loadedPing", x.loadedPing).put("loadedJitter", x.loadedJitter.finiteOrZero()).put("failedProbes", x.failedProbes).put("probeCount", x.probeCount).put("networkType", x.networkType).put("internetAvailable", x.internetAvailable).put("wifiSignal", x.wifiSignal).put("wifiQuality", x.wifiQuality).put("wifiLink", x.wifiLink).put("wifiFrequency", x.wifiFrequency).put("connectionNote", x.connectionNote).put("discoveryStatus", x.discoveryStatus).put("discoveryMs", x.discoveryMs).put("wifiSsid", x.wifiSsid).put("wifiBssid", x.wifiBssid)) }
                                                TestHistoryStore.write(context, arr); logDiagnostic("RESULT download=" + "%.2f".format(java.util.Locale.US, download) + " Mbps, upload=" + "%.2f".format(java.util.Locale.US, upload) + " Mbps, idlePing=" + ping + " ms, jitter=" + "%.2f".format(java.util.Locale.US, jitter) + " ms"); logDiagnostic("Stability: loadedPing=" + loadedPing + " ms, loadedJitter=" + "%.2f".format(java.util.Locale.US, loadedJitter) + " ms, failedProbes=" + failedProbes + "/" + probeCount); logDiagnostic("========== SPEED TEST SUCCESS =========="); status = "Test complete"; activityLog.add("Test completed successfully"); activityLog.add("Download: " + String.format(java.util.Locale.US, "%.1f Mbps", download) + " · Upload: " + String.format(java.util.Locale.US, "%.1f Mbps", upload)); activityLog.add("Loaded TCP ping: " + (if (loadedPing >= 0) "$loadedPing ms" else "unavailable") + " · failed probes $failedProbes/$probeCount"); while (activityLog.size > 8) activityLog.removeAt(0)
                                            } catch (e: Exception) { if (e is CancellationException) throw e; if (serverDiscoveryStatus == "Discovering…") serverDiscoveryStatus = "Failed"; logDiagnostic("FATAL " + e.javaClass.name + ": " + (e.message ?: "Test failed")); e.cause?.let { logDiagnostic("CAUSE " + it.javaClass.name + ": " + (it.message ?: "no message")) }; e.stackTrace.take(8).forEach { logDiagnostic("  at " + it.toString()) }; error = e.message ?: "Test failed"; status = "Test failed"; activityLog.add("ERROR: " + (e.message ?: "Test failed")); while (activityLog.size > 8) activityLog.removeAt(0) }
                                            finally { testing = false; gaugeUpload = false; progress = 1f; netInfo = readNetworkInfo(context) }
                                        }
                                    }
                                }, enabled = !testing && selectedServer != null, modifier = Modifier.fillMaxWidth().height(58.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (testing) "TEST RUNNING…" else if (selectedServer == null) "SELECT A SERVER FIRST" else "START TEST", fontWeight = FontWeight.Bold) }
                                if (testing) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    item {
                        val previous = history.firstOrNull()
                        val details = HomeMetricsSnapshot(
                            download = if (testing) liveDownloadMbps.toDouble()
                                else previous?.download ?: download.takeIf { it > 0.0 },
                            upload = if (testing) liveUploadMbps.toDouble()
                                else previous?.upload ?: upload.takeIf { it > 0.0 },
                            downloadAverage = if (testing) downloadAverage
                                else previous?.downloadAverage,
                            downloadPeak = if (testing) downloadPeak else previous?.downloadPeak,
                            uploadAverage = if (testing) uploadAverage else previous?.uploadAverage,
                            uploadPeak = if (testing) uploadPeak else previous?.uploadPeak,
                            loadedPing = if (testing) loadedPing else previous?.loadedPing ?: loadedPing,
                            loadedJitter = if (testing) loadedJitter else previous?.loadedJitter,
                            network = netInfo.type,
                            ssid = netInfo.ssid,
                            bssid = netInfo.bssid,
                            connected = netInfo.connected,
                            signal = netInfo.signal,
                            quality = netInfo.quality,
                            linkSpeed = netInfo.link,
                            frequency = netInfo.frequency,
                            serverName = selectedServer?.sponsor?.ifBlank { "Ookla server" }
                                ?: "No test server selected",
                            serverHost = selectedServer?.host.orEmpty(),
                            serverLocation = selectedServer?.city?.ifBlank { "Mauritius" }
                                ?: "Mauritius"
                        )
                        HomeDetailedMetrics(
                            expanded = showExtendedMetrics,
                            onToggle = { showExtendedMetrics = !showExtendedMetrics },
                            metrics = details
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeShortcut(Icons.Default.Wifi, "Wi-Fi Analyser", "Nearby networks",
                                Color(0xFF9C65EE), { activeTab = 5 }, Modifier.weight(1f))
                            HomeShortcut(Icons.Default.History, "Results", "Test history",
                                Color(0xFFF36BA3), { activeTab = 1 }, Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeShortcut(Icons.Default.Bolt, "Test Servers", "Mauritius servers",
                                Color(0xFF38D19A), { activeTab = 6 }, Modifier.weight(1f))
                            HomeShortcut(Icons.Default.NetworkCheck, "Network Info", "SSID and details",
                                Color(0xFF8D9BB6), { activeTab = 7 }, Modifier.weight(1f))
                        }
                    }
                }
                1 -> SpeedTestHistoryScreen(
                    history = history,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    export = {

                        val csv = SpeedHistoryCsv.export(history)
                                val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }; val file = File(exportDir, "speedtester-history.csv"); file.writeText(csv)
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export history"))
                            
                    },
                    clear = {
                        TestHistoryStore.write(context, org.json.JSONArray())
                        history.clear()
                    },
                    open = { result -> selectedHistory = result; activeTab = 8 }
                )
                8 -> {
                    val result = selectedHistory
                    if (result == null) {
                        LaunchedEffect(Unit) { activeTab = 1 }
                    } else {
                        SpeedTestResultDetails(
                            result = result,
                            modifier = Modifier.fillMaxSize().padding(padding),
                            back = { selectedHistory = null; activeTab = 1 },
                            share = { shareTestResult(context, result) },
                            delete = {
history.remove(result)
                                        val updatedHistory = org.json.JSONArray()
                                        history.forEach { x -> updatedHistory.put(JSONObject().put("date", x.date).put("download", x.download.finiteOrZero()).put("upload", x.upload.finiteOrZero()).put("ping", x.ping).put("jitter", x.jitter.finiteOrZero()).put("server", x.server).put("downloadAverage", x.downloadAverage.finiteOrZero()).put("downloadPeak", x.downloadPeak.finiteOrZero()).put("uploadAverage", x.uploadAverage.finiteOrZero()).put("uploadPeak", x.uploadPeak.finiteOrZero()).put("loadedPing", x.loadedPing).put("loadedJitter", x.loadedJitter.finiteOrZero()).put("failedProbes", x.failedProbes).put("probeCount", x.probeCount).put("networkType", x.networkType).put("internetAvailable", x.internetAvailable).put("wifiSignal", x.wifiSignal).put("wifiQuality", x.wifiQuality).put("wifiLink", x.wifiLink).put("wifiFrequency", x.wifiFrequency).put("connectionNote", x.connectionNote).put("discoveryStatus", x.discoveryStatus).put("discoveryMs", x.discoveryMs).put("wifiSsid", x.wifiSsid).put("wifiBssid", x.wifiBssid)) }
                                        TestHistoryStore.write(context, updatedHistory)
                                selectedHistory = null
                                activeTab = 1
                            }
                        )
                    }
                }
                6 -> SpeedServerDirectory(
                    servers = serversWithDistance,
                    selected = selectedServer,
                    loading = serversLoading,
                    error = serversError,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onSelect = { candidate ->
                        if (!testing) { selectedServer = candidate; activeTab = 0 }
                    },
                    onRefresh = {

                                        scope.launch {
                                            serversLoading = true; serversError = ""; serverDiscoveryStatus = "Refreshing Mauritius server directory…"
                                            val discoveryStarted = System.nanoTime()
                                            try {
                                                val refreshed = withContext(Dispatchers.IO) { SpeedEngine.discoverCandidates() }
                                                availableServers = refreshed
                                                serverDiscoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - discoveryStarted)
                                                if (refreshed.isEmpty()) {
                                                    serversError = "No HTTPS-compatible Ookla servers were found in Mauritius. The directory may not currently list a local server."
                                                    serverDiscoveryStatus = "No compatible servers found"
                                                } else serverDiscoveryStatus = "Complete · ${refreshed.size} Mauritius servers"
                                                if (selectedServer != null && refreshed.none { it.host == selectedServer?.host }) selectedServer = null
                                            } catch (e: Exception) {
                                                serversError = e.message ?: "Server refresh failed"
                                                serverDiscoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - discoveryStarted)
                                                serverDiscoveryStatus = "Failed"
                                            } finally { serversLoading = false }
                                        }
                                    
                    }
                )
                7 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Current Connection", style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold)
                                InfoRow("Network", netInfo.type)
                                if (netInfo.type == "Wi-Fi") {
                                    InfoRow("Wi-Fi name (SSID)", netInfo.ssid)
                                    InfoRow("Access point (BSSID)", netInfo.bssid)
                                    InfoRow("Wi-Fi signal", netInfo.signal)
                                    InfoRow("Signal quality", netInfo.quality)
                                    InfoRow("Link speed", netInfo.link)
                                    InfoRow("Frequency / band", netInfo.frequency)
                                    if (netInfo.ssid == "Unavailable") {
                                        Text("To display the network name, Android needs precise Location permission and enabled Location services.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { activeTab = 5 }) {
                                            Text("Open Wi-Fi Analyser to grant permission")
                                        }
                                    }
                                }
                                InfoRow("Internet capability",
                                    if (netInfo.connected) "Available" else "Unavailable")
                                InfoRow("Connection note", netInfo.note)
                            }
                        }
                    }
                }
                2 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Server map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    if (serversLoading || serverDiscoveryMs < 0L || availableServers.isEmpty()) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (serversLoading || serverDiscoveryMs < 0L) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        when {
                                            serversLoading || serverDiscoveryMs < 0L -> "Server map is disabled until discovery finishes."
                                            else -> "No Mauritius servers are available to plot. Refresh discovery on the Test page."
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    } else {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Mauritius test servers", fontWeight = FontWeight.Bold)
                                    Text("${serversWithDistance.count { it.latitude != null && it.longitude != null }} of  ${serversWithDistance.size} servers have map coordinates. Tap a marker for provider, city, distance and ping.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    val mapped = serversWithDistance.filter { it.latitude != null && it.longitude != null }
                                    if (mapped.isNotEmpty()) {
                                        ServerMapView(mapped, distanceOrigin.first, distanceOrigin.second, Modifier.fillMaxWidth().height(420.dp), userLocated = userLocated, onMapLog = { message ->
                                            val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
                                            activityLog.add("MAP $stamp · $message")
                                            while (activityLog.size > 8) activityLog.removeAt(0)
                                            logDiagnostic("MAP $message")
                                        })
                                        Text("Server locations are approximate directory coordinates, not exact equipment addresses. © OpenStreetMap contributors.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        Box(Modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp)).padding(18.dp), contentAlignment = Alignment.Center) {
                                            Text("The discovered server directory did not provide coordinates for these servers.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Servers · nearest first", fontWeight = FontWeight.Bold)
                                    serversWithDistance.forEachIndexed { index, server ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("${index + 1}.", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Column(Modifier.weight(1f)) {
                                                Text(server.label, fontWeight = FontWeight.SemiBold)
                                                Text(server.city.ifBlank { server.host }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text(server.distanceKm?.let { "%.1f km".format(java.util.Locale.US, it) } ?: "— km", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                        }
                                        if (index < serversWithDistance.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> if (!showUpdateDetails) LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                ) {
                    item {
                        Text(
                            "Manage your speed tests",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 12.dp),
                        )
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text("Test history") },
                            supportingContent = { Text("Previous speed tests and CSV export") },
                            leadingContent = { Icon(Icons.Default.History, null) },
                            modifier = Modifier.clickable { activeTab = 1 },
                        )
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text("Server map") },
                            supportingContent = { Text("Discovered Mauritius speed-test servers") },
                            leadingContent = { Icon(Icons.Default.Bolt, null) },
                            modifier = Modifier.clickable {
                                if (!serversLoading && serverDiscoveryMs >= 0L) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                        requestDeviceLocation(context, onDeviceLocation)
                                    } else locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                                }
                                activeTab = 2
                            },
                        )
                        ListItem(
                            headlineContent = { Text("Wi-Fi Analyser") },
                            supportingContent = { Text("Nearby networks, live graphs and channel recommendations") },
                            leadingContent = { Icon(Icons.Default.Wifi, null) },
                            modifier = Modifier.clickable { activeTab = 5; showUpdateDetails = false },
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("Logs") },
                            supportingContent = { Text("Private diagnostic and crash history") },
                            leadingContent = { Icon(Icons.Rounded.Article, contentDescription = null) },
                            modifier = Modifier.clickable { activeTab = 4; showUpdateDetails = false },
                        )
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text("About & updates") },
                            supportingContent = { Text(if (updateStatus.hasPendingUpdate()) "A new update is available" else "Version and update checks") },
                            leadingContent = { Icon(Icons.Default.Update, null) },
                            trailingContent = { dev.n2k02.updater.N2K02UpdateBadge(updateStatus) },
                            modifier = Modifier.clickable { showUpdateDetails = true },
                        )
                    }
                } else LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                ) {
                    item {
                        val experimental by LiveUpdater.experimentalEnabled.collectAsState()
                        dev.n2k02.updater.N2K02UpdateScreen(
                            controller = LiveUpdater.controller,
                            installGuard = LiveUpdater.installGuard,
                            experimentalEnabled = experimental,
                            onExperimentalEnabledChange = LiveUpdater::setExperimentalEnabled,
                        )
                    }
                }

            }
        }
    }
}


@Composable
private fun UpdateSectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable private fun Metric(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall); Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge); Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
}
@Composable
private fun InfoRow(label: String, value: String) {
    val verbose = value.length > 25 || label.length > 23
    if (verbose) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold)
        }
    }
}
@Composable private fun StatTile(label: String, value: String, unit: String, tint: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(value, color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
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
private data class NetworkInfo(val type: String, val connected: Boolean, val signal: String, val quality: String, val link: String, val frequency: String, val note: String, val ssid: String, val bssid: String)
@Suppress("DEPRECATION")
private fun readNetworkInfo(context: Context): NetworkInfo {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    // Prefer the active network's WifiInfo on modern Android; fall back for older releases.
    val info = (caps?.transportInfo as? android.net.wifi.WifiInfo) ?: runCatching { wifi.connectionInfo }.getOrNull()
    val type = when {
        caps == null -> "Disconnected"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Other"
    }
    val isWifi = type == "Wi-Fi"
    val freq = if (isWifi && info != null && info.frequency > 0) (if (info.frequency >= 5925) "6 GHz" else if (info.frequency >= 4900) "5 GHz" else "2.4 GHz") + " (" + info.frequency + " MHz)" else "—"
    val hasWifiInfo = isWifi && info?.rssi?.let { it in -126..0 } == true
    val rssi = if (hasWifiInfo) info!!.rssi else 0
    val quality = when { !hasWifiInfo -> "Unavailable"; rssi >= -50 -> "Excellent"; rssi >= -60 -> "Very good"; rssi >= -67 -> "Good"; rssi >= -75 -> "Fair"; else -> "Weak" }
    val note = when {
        !isWifi -> "Wi-Fi is not the active network"
        !hasWifiInfo -> "Android is restricting Wi-Fi signal details; link speed and band may still be available"
        else -> "Signal is refreshed every 2.5 seconds; RSSI is an Android-reported estimate"
    }
    val allowedName = isWifi &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        runCatching {
            (context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled
        }.getOrDefault(false)
    val direct = if (allowedName) runCatching { wifi.connectionInfo }.getOrNull() else null
    val rawName = if (allowedName) listOf(info?.ssid, direct?.ssid)
        .mapNotNull { it?.trim('"') }
        .firstOrNull { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
        .orEmpty() else ""
    val ssid = rawName.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", true) } ?: "Unavailable"
    val rawBssid = if (allowedName) listOf(info?.bssid, direct?.bssid)
        .firstOrNull { !it.isNullOrBlank() &&
            it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }.orEmpty() else ""
    val bssid = rawBssid.takeIf {
        it.isNotBlank() && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00"
    } ?: "Unavailable"
    return NetworkInfo(type, caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true, if (hasWifiInfo) "$rssi dBm" else "—", quality, if (isWifi && info != null && info.linkSpeed > 0) info.linkSpeed.toString() + " Mbps" else "—", freq, note, ssid, bssid)
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
        Text("Server did not provide TCP_INFO samples for this direction.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
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
private const val USER_LOCATION_ZOOM = 11.0

/**
 * Server map drawn natively in Compose (see OsmMap.kt). Controls are Compose overlays; the camera is
 * re-framed only when the server data or distance origin changes, not on every recomposition.
 */
@Composable
private fun ServerMapView(
    servers: List<NdtServerCandidate>,
    originLat: Double,
    originLon: Double,
    modifier: Modifier = Modifier,
    userLocated: Boolean = false,
    onMapLog: (String) -> Unit = {}
) {
    val density = LocalDensity.current
    val tileSize = mapTileSizePx(density.density)
    val framePadding = with(density) { 56.dp.toPx() }
    var tileSource by remember { mutableStateOf(MapTileSources.Standard) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val camera = remember { MapCameraState(originLat, originLon, 8.0) }

    val markers = remember(servers, originLat, originLon, userLocated) {
        buildList {
            add(
                if (userLocated) MapMarker("origin", originLat, originLon, "Your location", "Approximate device location, used for server distances", isOrigin = true)
                else MapMarker("origin", originLat, originLon, "Distance reference", "Your location is unavailable, so distances use central Mauritius", isOrigin = true)
            )
            servers.forEachIndexed { index, server ->
                val lat = server.latitude
                val lon = server.longitude
                if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    val distance = server.distanceKm?.let { String.format(java.util.Locale.US, "%.1f km away", it) } ?: "Distance unavailable"
                    val ping = server.latencyMs?.let { "$it ms TCP ping" } ?: "TCP ping unavailable"
                    add(MapMarker(server.serverId.ifBlank { server.host + "#" + index }, lat, lon, server.label, "${server.city}\n$distance · $ping\n${server.host}"))
                }
            }
        }
    }
    val serverPoints = remember(markers) { markers.filter { !it.isOrigin }.map { it.latitude to it.longitude } }

    // Start on the user's location when it is known; otherwise frame every server pin.
    // Keyed on a ~1 km rounded position so small location refinements don't yank the map around.
    val originKey = if (userLocated) "%.2f,%.2f".format(java.util.Locale.US, originLat, originLon) else "unlocated"
    val mapReady = viewSize.width > 0 && viewSize.height > 0
    LaunchedEffect(originKey, mapReady, if (userLocated) Unit else markers) {
        if (!mapReady) return@LaunchedEffect
        if (userLocated) {
            camera.moveTo(originLat, originLon, USER_LOCATION_ZOOM)
            onMapLog("Map centred on your location; zoom=$USER_LOCATION_ZOOM")
        } else {
            camera.fit(markers.map { it.latitude to it.longitude }, viewSize.width, viewSize.height, framePadding, tileSize)
            onMapLog("Location unavailable; map framed on all servers; zoom=${"%.1f".format(java.util.Locale.US, camera.zoom)}")
        }
    }

    // Explain pin counts: servers in the same city share one coordinate and are drawn as one numbered pin.
    LaunchedEffect(servers) {
        val located = servers.filter { it.latitude != null && it.longitude != null }
        val distinct = located.map { "%.4f,%.4f".format(java.util.Locale.US, it.latitude, it.longitude) }.distinct().size
        onMapLog("Servers on map=${located.size}; distinct pin locations=$distinct; without coordinates=${servers.size - located.size}")
        located.forEach { onMapLog("Server ${it.serverId.ifBlank { it.host }} ${it.sponsor.ifBlank { it.host }} · ${it.city} @ ${it.latitude},${it.longitude}") }
    }

    Box(modifier = modifier) {
        OsmTileMap(
            markers = markers,
            source = tileSource,
            camera = camera,
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
            reloadToken = reloadToken,
            onMapLog = onMapLog
        )

        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MapTileSources.all.forEach { source ->
                TextButton(
                    onClick = {
                        if (tileSource != source) {
                            tileSource = source
                            onMapLog("Tile style changed to ${source.name}")
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(source.name, color = if (tileSource == source) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            TextButton(
                onClick = {
                    reloadToken++
                    onMapLog("Manual reload requested; source=${tileSource.name}")
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text("Reload", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val center = Offset(viewSize.width / 2f, viewSize.height / 2f)
            TextButton(onClick = { camera.zoomAround(center, viewSize.width, viewSize.height, tileSize, kotlin.math.floor(camera.zoom) + 1.0) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                Text("+", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = { camera.zoomAround(center, viewSize.width, viewSize.height, tileSize, kotlin.math.ceil(camera.zoom) - 1.0) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                Text("−", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = { camera.moveTo(originLat, originLon, if (userLocated) USER_LOCATION_ZOOM else 9.0) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Here", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = {
                if (serverPoints.isNotEmpty()) camera.fit(serverPoints, viewSize.width, viewSize.height, framePadding, tileSize)
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Fit", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
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
private fun assessQuality(download: Double, upload: Double, ping: Long, jitter: Double, loadedPing: Long, failed: Int, probes: Int): QualityAssessment {
    if (download <= 0.0 && upload <= 0.0 && ping < 0L) return QualityAssessment(0, "Not rated", "Run a speed test to calculate a score.")
    val downScore = (download / 100.0).coerceIn(0.0, 1.0) * 35.0
    val upScore = (upload / 50.0).coerceIn(0.0, 1.0) * 20.0
    val pingScore = if (ping < 0L) 0.0 else (1.0 - ((ping - 15L).coerceAtLeast(0L) / 185.0)).coerceIn(0.0, 1.0) * 20.0
    val jitterScore = (1.0 - (jitter / 50.0)).coerceIn(0.0, 1.0) * 10.0
    val loadedScore = if (loadedPing < 0L || ping < 0L) 0.0 else (1.0 - ((loadedPing - ping).coerceAtLeast(0L) / 250.0)).coerceIn(0.0, 1.0) * 10.0
    val stabilityScore = if (probes <= 0) 0.0 else ((probes - failed).coerceAtLeast(0).toDouble() / probes) * 5.0
    val score = (downScore + upScore + pingScore + jitterScore + loadedScore + stabilityScore).toInt().coerceIn(0, 100)
    val label = when { score >= 85 -> "Excellent"; score >= 70 -> "Very good"; score >= 50 -> "Good"; score >= 30 -> "Fair"; else -> "Needs attention" }
    return QualityAssessment(score, label, "A SpeedTester estimate based on speed, latency, jitter and stability—not an official Ookla result.")
}
internal data class NdtServerCandidate(
    val host: String, val city: String, val country: String, val countryName: String,
    val downloadUrl: String, val uploadUrl: String,
    val latitude: Double? = null, val longitude: Double? = null,
    val distanceKm: Double? = null, val latencyMs: Long? = null,
    val serverId: String = "", val sponsor: String = ""
) {
    val label: String get() = (sponsor.ifBlank { "Ookla server" }) + " · " + city.ifBlank { host } + (if (serverId.isNotBlank()) " (#$serverId)" else "")
}
private fun distanceKmBetween(originLat: Double, originLon: Double, lat: Double?, lon: Double?): Double? {
    if (lat == null || lon == null) return null
    val targetLat = Math.toRadians(lat); val targetLon = Math.toRadians(lon)
    val startLat = Math.toRadians(originLat); val startLon = Math.toRadians(originLon)
    val dLat = targetLat - startLat; val dLon = targetLon - startLon
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) + kotlin.math.cos(startLat) * kotlin.math.cos(targetLat) * kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return 6371.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}
private fun getLastKnownLocation(context: Context): Location? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstNotNullOfOrNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
}
/**
 * Reports the device location: the last known fix immediately (if any), then a fresh fix from the
 * fused or network provider when it arrives. Needs only coarse location; does nothing without permission.
 */
private fun requestDeviceLocation(context: Context, onLocation: (Location) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
    getLastKnownLocation(context)?.let(onLocation)
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val provider = when {
        android.os.Build.VERSION.SDK_INT >= 31 && runCatching { manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) }.getOrDefault(false) -> LocationManager.FUSED_PROVIDER
        runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
        else -> return
    }
    runCatching {
        LocationManagerCompat.getCurrentLocation(
            manager, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(context)
        ) { location -> if (location != null) onLocation(location) }
    }
}
private object SpeedEngine {
    private const val SERVERS_URL = "https://www.speedtest.net/api/js/servers?engine=js&https_functional=true&limit=1000"
    private const val TEST_SECONDS = 8L
    /** Parallel connections per direction. One connection can't fill a fast line (TCP slow start, per-connection limits). */
    private const val STREAMS = 4
    /** The first part of each phase is TCP ramp-up; the headline speed is measured after it. */
    private const val WARMUP_MS = 1500L
    private val discoveryClient = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        // No overall call timeout: transfers are stopped by the test clock, not by OkHttp.
        .retryOnConnectionFailure(false)
        // HTTP/1.1 keeps each stream on its own TCP connection (HTTP/2 would share one).
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

    suspend fun discoverCandidates(): List<NdtServerCandidate> = withContext(Dispatchers.IO) {
        // Search terms improve coverage, but the local country-code check is authoritative.
        val searches = listOf("Mauritius", "my.t", "Mauritius Telecom", "Emtel", "CHiLi", "4342", "18685", "30612")
        // All searches run at once instead of one after another.
        val pages = kotlinx.coroutines.coroutineScope {
            searches.map { term ->
                async {
                    runCatching {
                        val url = SERVERS_URL + "&search=" + java.net.URLEncoder.encode(term, "UTF-8")
                        discoveryClient.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "SpeedTester-Android/1.0").build()).execute().use { response ->
                            if (!response.isSuccessful) "[]" else response.body?.string() ?: "[]"
                        }
                    }.getOrDefault("[]")
                }
            }.awaitAll()
        }
        val found = linkedMapOf<String, NdtServerCandidate>()
        for (jsonText in pages) {
            val json = runCatching { JSONArray(jsonText) }.getOrNull() ?: continue
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val country = item.optString("cc").uppercase(java.util.Locale.ROOT)
                if (country != "MU") continue
                if (item.optInt("https_functional", 0) != 1) continue
                val rawUrl = item.optString("url")
                if (rawUrl.isBlank()) continue
                val secureUrl = runCatching {
                    val u = java.net.URI(rawUrl)
                    if (u.host.isNullOrBlank()) "" else java.net.URI("https", u.userInfo, u.host, u.port, u.path, u.query, u.fragment).toString()
                }.getOrDefault("")
                if (!secureUrl.startsWith("https://")) continue
                val parsed = runCatching { java.net.URI(secureUrl) }.getOrNull() ?: continue
                val hostPort = item.optString("host").ifBlank { parsed.rawAuthority.orEmpty() }
                val id = item.optString("id")
                val key = id.ifBlank { hostPort }
                if (found.containsKey(key)) continue
                val city = item.optString("name").takeUnless { it == "null" }.orEmpty()
                val sponsor = item.optString("sponsor").takeUnless { it == "null" }.orEmpty()
                val lat = item.optString("lat").toDoubleOrNull()
                val lon = item.optString("lon").toDoubleOrNull()
                found[key] = NdtServerCandidate(
                    hostPort, city, country, "Mauritius", secureUrl, secureUrl,
                    lat, lon, distanceKmBetween(-20.2, 57.5, lat, lon), null, id, sponsor
                )
            }
        }
        // Latency to every server, measured in parallel too.
        val measured = kotlinx.coroutines.coroutineScope {
            found.values.map { candidate ->
                async {
                    val hostPort = candidate.host
                    val host = hostPort.substringBeforeLast(":", hostPort)
                    val port = hostPort.substringAfterLast(":", "").toIntOrNull() ?: 443
                    val latency = runCatching {
                        val started = System.nanoTime()
                        Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1800) }
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    }.getOrNull()
                    candidate.copy(latencyMs = latency)
                }
            }.awaitAll()
        }
        measured.filter { it.country == "MU" }
            .sortedWith(compareBy<NdtServerCandidate> { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.distanceKm ?: Double.MAX_VALUE }.thenBy { it.sponsor })
            .take(30)
    }

    /** Result of one direction: headline (after ramp-up), whole-phase average, peak, bytes and seconds. */
    private data class Phase(val mbps: Double, val averageMbps: Double, val peakMbps: Double, val bytes: Long, val seconds: Double, val latency: List<Long>, val failedProbes: Int, val attemptedProbes: Int) {
        val loadedPing: Long get() = latency.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: -1L
        val loadedJitter: Double get() = if (latency.size < 2) 0.0 else latency.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average()
    }

    /**
     * Runs [STREAMS] transfers in parallel for [TEST_SECONDS], reporting the combined rate about every
     * 250 ms. [transfer] performs one request; it adds to the counter as bytes move, and should stop
     * when the flag is set. Calls still running at the end are cancelled.
     */
    /** Tracks acknowledged upload bytes and latency while data transfer is active. */
    private suspend fun runPhase(
        label: String, from: Float, to: Float, host: String, port: Int, verifyUpload: Boolean,
        onUpdate: (String, Float, Float) -> Unit, onLog: (String) -> Unit,
        transfer: (
            stop: java.util.concurrent.atomic.AtomicBoolean,
            sent: java.util.concurrent.atomic.AtomicLong,
            acknowledged: java.util.concurrent.atomic.AtomicLong,
            calls: MutableSet<okhttp3.Call>
        ) -> Unit,
    ): Phase = kotlinx.coroutines.coroutineScope {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val sent = java.util.concurrent.atomic.AtomicLong()
        val acknowledged = java.util.concurrent.atomic.AtomicLong()
        val calls: MutableSet<okhttp3.Call> = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<okhttp3.Call, Boolean>())
        val sockets: MutableSet<Socket> = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
        val failures = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val probeFailures = java.util.concurrent.atomic.AtomicInteger()
        val probeAttempts = java.util.concurrent.atomic.AtomicInteger()
        val latency = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val workers = (1..STREAMS).map { n ->
            launch(Dispatchers.IO) {
                var retries = 0
                while (!stop.get()) {
                    try {
                        transfer(stop, sent, acknowledged, calls)
                        retries = 0
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (stop.get()) break
                        failures.compareAndSet(null, e)
                        onLog(label + " stream " + n + ": " + e.javaClass.simpleName)
                        if (++retries >= 3) break
                        kotlinx.coroutines.delay(300)
                    }
                }
            }
        }
        val probeWorker = launch(Dispatchers.IO) {
            repeat(7) {
                kotlinx.coroutines.delay(350)
                if (stop.get()) return@launch
                val socket = Socket()
                sockets.add(socket)
                probeAttempts.incrementAndGet()
                try {
                    val started = System.nanoTime()
                    socket.connect(java.net.InetSocketAddress(host, port), 1100)
                    if (!stop.get()) latency.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (!stop.get()) probeFailures.incrementAndGet()
                } finally {
                    sockets.remove(socket)
                    runCatching { socket.close() }
                }
                kotlinx.coroutines.delay(400)
            }
        }
        try {
            val start = System.nanoTime()
            val duration = TimeUnit.SECONDS.toNanos(TEST_SECONDS)
            var lastAt = start
            var lastBytes = 0L
            var peak = 0.0
            var warmupBytes = -1L
            var warmupAt = 0L
            while (System.nanoTime() - start < duration) {
                kotlinx.coroutines.delay(250)
                val now = System.nanoTime()
                val total = TransferAccounting.countableBytes(sent.get(), acknowledged.get(), verifyUpload)
                val interval = ((now - lastAt) / 1_000_000_000.0).coerceAtLeast(.001)
                val instant = (total - lastBytes).coerceAtLeast(0L) * 8.0 / interval / 1_000_000.0
                if (warmupBytes < 0 && now - start >= TimeUnit.MILLISECONDS.toNanos(WARMUP_MS)) {
                    warmupBytes = total
                    warmupAt = now
                }
                peak = maxOf(peak, instant)
                val fraction = ((now - start).toDouble() / duration).toFloat().coerceIn(0f, 1f)
                onUpdate(label, from + (to - from) * fraction, instant.toFloat())
                lastAt = now
                lastBytes = total
                if (workers.all { it.isCompleted }) break
            }
            stop.set(true)
            calls.toList().forEach { it.cancel() }
            sockets.toList().forEach { runCatching { it.close() } }
            probeWorker.cancelAndJoin()
            workers.forEach { it.join() }
            val end = System.nanoTime()
            val total = TransferAccounting.countableBytes(sent.get(), acknowledged.get(), verifyUpload)
            if (total <= 0L) error(label + ": no server-accepted data; " +
                (failures.get()?.javaClass?.simpleName ?: "no successful transfer"))
            val seconds = ((end - start) / 1_000_000_000.0).coerceAtLeast(.001)
            val average = total * 8.0 / seconds / 1_000_000.0
            val steady = if (warmupBytes >= 0L && end > warmupAt)
                (total - warmupBytes).coerceAtLeast(0L) * 8.0 /
                    ((end - warmupAt) / 1_000_000_000.0) / 1_000_000.0
                else average
            val samples = synchronized(latency) { latency.toList() }
            onLog(label + " concurrent loaded latency probes: " + samples.size + "/" + probeAttempts.get())
            Phase(steady, average, maxOf(peak, steady), total, seconds,
                samples, probeFailures.get(), probeAttempts.get())
        } finally {
            stop.set(true)
            calls.toList().forEach { it.cancel() }
            sockets.toList().forEach { runCatching { it.close() } }
            workers.forEach { it.cancel() }
            probeWorker.cancel()
        }
    }

    suspend fun run(base: String, selectedServer: NdtServerCandidate?, onServer: (ServerDetails) -> Unit, onUpdate: (String, Float, Float) -> Unit, onMetrics: (Long, Double) -> Unit, onLog: (String) -> Unit): SpeedOutcome = withContext(Dispatchers.IO) {
        val server = selectedServer ?: error("Select a Mauritius Ookla-compatible server first")
        val rawUploadUri = java.net.URI(server.uploadUrl)
        // Ookla-hosted servers can advertise a provider alias whose TLS certificate is issued
        // for the canonical server-ID hostname. Use that certificate-valid hostname; never
        // disable certificate or hostname verification.
        val certificateHost = server.serverId.takeIf { it.matches(Regex("\\d+")) }
            ?.let { "server-$it.prod.hosts.ooklaserver.net" }
            ?: rawUploadUri.host
            ?: error("The selected Ookla server URL is invalid")
        val uploadUri = java.net.URI(
            rawUploadUri.scheme, rawUploadUri.userInfo, certificateHost, rawUploadUri.port,
            rawUploadUri.path, rawUploadUri.query, rawUploadUri.fragment
        )
        val uploadEndpoint = uploadUri.toString()
        val host = uploadUri.host ?: error("The selected Ookla server URL is invalid")
        val port = if (uploadUri.port > 0) uploadUri.port else 443
        val locationName = listOf(server.sponsor, server.city, server.countryName).filter { it.isNotBlank() }.joinToString(" · ")
        onLog("Engine: Ookla-compatible HTTPS transfer test, $STREAMS parallel connections per direction")
        onLog("Discovery: Speedtest.net public server directory")
        onLog("Country filter: MU (enforced locally)")
        onLog("Selected server ID: " + server.serverId)
        onLog("Sponsor: " + server.sponsor)
        onLog("Server endpoint (certificate-valid Ookla hostname): " + uploadEndpoint)
        onLog("TLS: normal certificate and hostname verification enabled; no bypass")
        onServer(ServerDetails(server.host, server.city, server.country, server.latitude, server.longitude))

        val dnsStart = System.nanoTime()
        val resolvedAddress = runCatching { InetAddress.getAllByName(host).firstOrNull()?.hostAddress.orEmpty() }.getOrDefault("")
        val dnsLookupMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dnsStart)
        onLog("DNS lookup: " + (resolvedAddress.ifBlank { "failed" }) + " in " + dnsLookupMs + "ms")
        val pings = mutableListOf<Long>()
        repeat(8) { index ->
            val started = System.nanoTime()
            Socket().use { it.connect(java.net.InetSocketAddress(host, port), 3000) }
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            pings.add(ms)
            onLog("TCP latency probe " + (index + 1) + "/8: " + ms + "ms")
            onMetrics(pings.average().toLong(), if (pings.size > 1) pings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average() else 0.0)
            onUpdate("Measuring latency", .02f + .06f * pings.size / 8f, 0f)
            kotlinx.coroutines.delay(100)
        }
        val ping = pings.average().toLong()
        val jitter = pings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average()

        // Ookla classic servers expose random-size JPEG payloads beside upload.php. Each stream
        // requests it again whenever it finishes, until the time is up.
        val downloadBase = java.net.URI(uploadEndpoint).let { u -> u.path.substringBeforeLast("/", "") + "/random4000x4000.jpg" }
        onLog("Download endpoint: " + java.net.URI(uploadUri.scheme, null, uploadUri.host, uploadUri.port, downloadBase, null, null))
        onUpdate("Downloading · Ookla server", .08f, 0f)
        val down = runPhase("Downloading · Ookla server", .08f, .50f, host, port, false, onUpdate, onLog) { stop, counter, accepted, calls ->
            val url = java.net.URI(uploadUri.scheme, uploadUri.userInfo, uploadUri.host, uploadUri.port, downloadBase, "nocache=" + System.nanoTime(), null).toString()
            val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").header("User-Agent", "SpeedTester-Android/1.0").build())
            calls.add(call)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) error("Ookla download endpoint returned HTTP " + response.code + ". This server may not support the compatible transfer endpoint.")
                    val body = response.body ?: error("Ookla download endpoint returned an empty response")
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (!stop.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            counter.addAndGet(count.toLong())
                        }
                    }
                }
            } finally {
                calls.remove(call)
            }
        }
        onLog("DOWNLOAD: bytes=${down.bytes}, elapsed=" + "%.2f".format(java.util.Locale.US, down.seconds) + "s, after ramp-up=" + "%.2f".format(java.util.Locale.US, down.mbps) + " Mbps, whole phase=" + "%.2f".format(java.util.Locale.US, down.averageMbps) + " Mbps")

        onUpdate("Uploading · Ookla server", .55f, 0f)
        val payload = ByteArray(256 * 1024).also { java.security.SecureRandom().nextBytes(it) }
        val up = runPhase("Uploading · Ookla server", .55f, .95f, host, port, true, onUpdate, onLog) { stop, counter, accepted, calls ->
            val nonce = java.util.UUID.randomUUID().toString()
            val requestUrl = java.net.URI(uploadUri.scheme, uploadUri.userInfo, uploadUri.host, uploadUri.port, uploadUri.path, "nocache=$nonce", null).toString()
            // A 256 KiB request enables acceptance-based throughput even on slower connections.
            val body = object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = payload.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) {
                    repeat(1) {
                        var offset = 0
                        while (offset < payload.size) {
                            if (stop.get()) throw java.io.IOException("Test time is up")
                            val n = minOf(64 * 1024, payload.size - offset)
                            sink.write(payload, offset, n)
                            sink.flush()
                            counter.addAndGet(n.toLong())
                            offset += n
                        }
                    }
                }
            }
            val call = client.newCall(Request.Builder().url(requestUrl).post(body).header("Cache-Control", "no-cache").header("User-Agent", "SpeedTester-Android/1.0").build())
            calls.add(call)
            try {
                call.execute().use {
                    if (!it.isSuccessful) error("Ookla upload endpoint returned HTTP " + it.code)
                    accepted.addAndGet(payload.size.toLong())
                }
            } finally {
                calls.remove(call)
            }
        }
        onLog("UPLOAD: bytes=${up.bytes}, elapsed=" + "%.2f".format(java.util.Locale.US, up.seconds) + "s, after ramp-up=" + "%.2f".format(java.util.Locale.US, up.mbps) + " Mbps, whole phase=" + "%.2f".format(java.util.Locale.US, up.averageMbps) + " Mbps")

        onUpdate("Checking connection stability", .96f, 0f)
        val loadedPing = TransferAccounting.loadedPing(down.loadedPing, up.loadedPing)
        val loadedJitter = TransferAccounting.loadedJitter(down.loadedJitter, up.loadedJitter)
        val failed = down.failedProbes + up.failedProbes
        val totalProbes = down.attemptedProbes + up.attemptedProbes
        onLog("Concurrent loaded TCP latency: download=" + down.loadedPing + " ms, upload=" +
            up.loadedPing + " ms; combined worst=" + loadedPing + " ms")
        onLog("Ookla-compatible server: " + server.host + " (" + locationName + ")")
        onUpdate("Complete", 1f, 0f)
        SpeedOutcome(down.mbps, up.mbps, ping, jitter, down.averageMbps, down.peakMbps, up.averageMbps, up.peakMbps, loadedPing, loadedJitter, failed, totalProbes, null, null, resolvedAddress, dnsLookupMs)
    }
}
