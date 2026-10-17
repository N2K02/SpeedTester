package com.speedtester.app

import android.content.Context
import android.provider.Settings
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.max

private val Teal = Color(0xFF13B8A6)
private val Muted = Color(0xFF8A9AA5)
data class TestResult(val date: String, val download: Double, val upload: Double, val ping: Long, val jitter: Double, val server: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SpeedTesterApp(this) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedTesterApp(context: Context) {
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("speedtester", Context.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "https://speed.cloudflare.com") ?: "") }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to test") }
    var progress by remember { mutableFloatStateOf(0f) }
    var download by remember { mutableDoubleStateOf(0.0) }
    var upload by remember { mutableDoubleStateOf(0.0) }
    var ping by remember { mutableLongStateOf(-1L) }
    var jitter by remember { mutableDoubleStateOf(0.0) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableIntStateOf(0) }
    var updateMessage by remember { mutableStateOf("Tap Check for updates to see the latest release.") }
    var updateDetails by remember { mutableStateOf("") }
    var updateVersion by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    var updateAvailable by remember { mutableStateOf(false) }
    val activityLog = remember { mutableStateListOf("Ready. Start a test to see live diagnostics here.") }
    var netInfo by remember { mutableStateOf(readNetworkInfo(context)) }
    val samples = remember { mutableStateListOf<Float>() }
    val history = remember {
        runCatching {
            val arr = org.json.JSONArray(prefs.getString("history", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TestResult(o.optString("date"), o.optDouble("download"), o.optDouble("upload"), o.optLong("ping", -1), o.optDouble("jitter"), o.optString("server"))
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
                    NavigationBarItem(selected = activeTab == 2, onClick = { activeTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                    NavigationBarItem(selected = activeTab == 3, onClick = { activeTab = 3 }, icon = { Icon(Icons.Default.Update, null) }, label = { Text("Updates") })
                }
            }, containerColor = Color(0xFF0B1117)
        ) { padding ->
            when (activeTab) {
                0 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25)), shape = RoundedCornerShape(24.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(status, color = Muted, style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(8.dp))
                                Text(String.format("%.1f", download), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("DOWNLOAD Mbps", color = Teal, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(10.dp))
                                BandwidthChart(samples.toList(), Modifier.fillMaxWidth().height(150.dp))
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Metric("UPLOAD", if (upload > 0) String.format("%.1f", upload) else "—", "Mbps")
                                    Metric("PING", if (ping >= 0) ping.toString() else "—", "ms")
                                    Metric("JITTER", String.format("%.1f", jitter), "ms")
                                }
                                Spacer(Modifier.height(18.dp))
                                Button(onClick = {
                                    if (!testing) {
                                        testing = true; error = null; progress = 0f; samples.clear(); status = "Measuring latency…"; activityLog.clear(); activityLog.add("Starting test against " + endpoint.trim()); activityLog.add("Checking TCP connection latency…")
                                        scope.launch {
                                            try {
                                                val result = SpeedEngine.run(endpoint.trim().trimEnd('/'), { phase, value, mbps ->
                                                    status = phase; progress = value
                                                    if (activityLog.lastOrNull() != phase) { activityLog.add(phase + " phase started"); if (activityLog.size > 8) activityLog.removeAt(0) }
                                                    if (mbps > 0f) { samples.add(mbps); if (samples.size > 80) samples.removeAt(0); if (activityLog.lastOrNull()?.startsWith("Live rate:") != true) { activityLog.add("Live rate: " + String.format(java.util.Locale.US, "%.1f Mbps", mbps)); if (activityLog.size > 8) activityLog.removeAt(0) } else { activityLog[activityLog.lastIndex] = "Live rate: " + String.format(java.util.Locale.US, "%.1f Mbps", mbps) } }
                                                }, { p, j -> ping = p; jitter = j })
                                                download = result.download; upload = result.upload; ping = result.ping; jitter = result.jitter
                                                val saved = TestResult(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), download, upload, ping, jitter, endpoint)
                                                history.add(0, saved); while (history.size > 100) history.removeAt(history.lastIndex)
                                                val arr = org.json.JSONArray()
                                                history.forEach { x -> arr.put(JSONObject().put("date", x.date).put("download", x.download).put("upload", x.upload).put("ping", x.ping).put("jitter", x.jitter).put("server", x.server)) }
                                                prefs.edit().putString("history", arr.toString()).apply(); status = "Test complete"; activityLog.add("Test completed successfully"); activityLog.add("Download: " + String.format(java.util.Locale.US, "%.1f Mbps", download) + " · Upload: " + String.format(java.util.Locale.US, "%.1f Mbps", upload)); while (activityLog.size > 8) activityLog.removeAt(0)
                                            } catch (e: Exception) { error = e.message ?: "Test failed"; status = "Test failed"; activityLog.add("ERROR: " + (e.message ?: "Test failed")); while (activityLog.size > 8) activityLog.removeAt(0) }
                                            finally { testing = false; progress = 1f; netInfo = readNetworkInfo(context) }
                                        }
                                    }
                                }, enabled = !testing, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text(if (testing) "TEST RUNNING…" else "START SPEED TEST", fontWeight = FontWeight.Bold) }
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
                                InfoRow("Network", netInfo.type); InfoRow("Internet", if (netInfo.connected) "Connected" else "Not connected")
                                InfoRow("Wi-Fi signal", netInfo.signal); InfoRow("Wi-Fi link speed", netInfo.link); InfoRow("Frequency", netInfo.frequency)
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
                                val csv = buildString { append("date,download_mbps,upload_mbps,ping_ms,jitter_ms,server\n"); history.forEach { append("\"" + it.date + "\"," + it.download + "," + it.upload + "," + it.ping + "," + it.jitter + ",\"" + it.server + "\"\n") } }
                                val file = File(context.cacheDir, "speedtester-history.csv"); file.writeText(csv)
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export history"))
                            }) { Text("Export CSV") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { history.clear(); prefs.edit().putString("history", "[]").apply() }) { Text("Clear history") }
                        }
                    }
                    if (history.isEmpty()) item { Text("Your completed tests will appear here.", color = Muted) }
                    items(history) { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141D25))) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(item.date, color = Muted, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(6.dp))
                                Text("↓ %.1f Mbps    ↑ %.1f Mbps".format(item.download, item.upload), fontWeight = FontWeight.Bold)
                                Text("Ping " + item.ping + " ms  ·  Jitter %.1f ms".format(item.jitter), color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                2 -> LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    item { Text("LibreSpeed-compatible server base URL", color = Muted) }
                    item { OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server URL") }) }
                    item {
                        Button(onClick = {
                            val valid = runCatching { URL(endpoint).protocol in listOf("https", "http") }.getOrDefault(false)
                            if (valid) { prefs.edit().putString("endpoint", endpoint.trim().trimEnd('/')).apply(); updateMessage = "Server saved" } else updateMessage = "Enter a valid HTTP(S) URL"
                        }) { Text("Save server") }
                    }
                    item { Text("Default: Cloudflare public speed-test endpoint. For LibreSpeed, enter the backend directory URL containing garbage.php and empty.php, for example https://your-server.example/backend. Other providers need documented compatible endpoints.", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    item { Text("Data usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    item { Text("A full test transfers several megabytes. Tests run only on demand; no background speed testing.", color = Muted) }
                    item { Text("Updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    item { Text("Current version " + BuildConfig.VERSION_NAME + ". Tap the download icon above to check GitHub Releases.", color = Muted) }
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
                                    scope.launch { updateMessage = withContext(Dispatchers.IO) { UpdateChecker.downloadAndInstall(context, updateUrl) } }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Download and install") }
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
@Composable private fun BandwidthChart(samples: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF0D151B), RoundedCornerShape(12.dp)).padding(8.dp)) {
        if (samples.size < 2) return@Canvas
        val maxValue = max(1f, samples.maxOrNull() ?: 1f); val path = Path()
        samples.forEachIndexed { i, value ->
            val x = size.width * i / (samples.size - 1).toFloat(); val y = size.height - (value / maxValue) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Teal, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
        for (i in 1..3) drawLine(Color(0xFF26343E), Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
    }
}
private data class NetworkInfo(val type: String, val connected: Boolean, val signal: String, val link: String, val frequency: String)
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
    return NetworkInfo(type, caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true, if (isWifi && info.networkId != -1) info.rssi.toString() + " dBm" else "—", if (isWifi && info.linkSpeed > 0) info.linkSpeed.toString() + " Mbps" else "—", freq)
}
private data class SpeedOutcome(val download: Double, val upload: Double, val ping: Long, val jitter: Double)
private object SpeedEngine {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    suspend fun run(base: String, onUpdate: (String, Float, Float) -> Unit, onMetrics: (Long, Double) -> Unit): SpeedOutcome = withContext(Dispatchers.IO) {
        require(base.startsWith("https://") || base.startsWith("http://")) { "Server URL must start with HTTP or HTTPS" }
        val parsed = URL(base); val host = parsed.host; val port = if (parsed.port > 0) parsed.port else if (base.startsWith("https")) 443 else 80
        val pings = mutableListOf<Long>()
        repeat(5) {
            val start = System.nanoTime()
            Socket().use { it.connect(java.net.InetSocketAddress(host, port), 3000) }
            pings.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
            val avg = pings.average().toLong()
            val j = if (pings.size > 1) pings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average() else 0.0
            onMetrics(avg, j)
        }
        val ping = pings.average().toLong()
        val jitter = if (pings.size > 1) pings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average() else 0.0
        onUpdate("Downloading", .08f, 0f)
        val cloudflare = URL(base).host.equals("speed.cloudflare.com", ignoreCase = true)
        val downloadUrl = if (cloudflare) base + "/__down?bytes=8000000&nocache=" + System.nanoTime() else base + "/garbage.php?cors=true&ckSize=8&nocache=" + System.nanoTime()
        val request = Request.Builder().url(downloadUrl).header("Cache-Control", "no-cache").build()
        val started = System.nanoTime(); var bytes = 0L
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP " + response.code + ". Check server URL." }
            val stream = response.body?.byteStream() ?: error("Empty download response"); val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break; bytes += n
                val fraction = (bytes.toDouble() / 8_000_000.0).coerceIn(0.0, 1.0)
                val elapsed = (System.nanoTime() - started).coerceAtLeast(1) / 1_000_000_000.0
                val liveMbps = (bytes * 8.0 / elapsed / 1_000_000.0).toFloat()
                onUpdate("Downloading", (.08 + .42 * fraction).toFloat(), liveMbps)
            }
        }
        val downloadMbps = bytes * 8.0 / ((System.nanoTime() - started).coerceAtLeast(1) / 1_000_000_000.0) / 1_000_000.0
        onUpdate("Uploading", .55f, 0f)
        val payload = ByteArray(2_000_000); java.security.SecureRandom().nextBytes(payload)
        val uploadUrl = if (cloudflare) base + "/__up?nocache=" + System.nanoTime() else base + "/empty.php?cors=true&nocache=" + System.nanoTime()
        val uploadRequest = Request.Builder().url(uploadUrl).post(payload.toRequestBody(null)).header("Cache-Control", "no-cache").build()
        val uploadStart = System.nanoTime()
        client.newCall(uploadRequest).execute().use { response -> check(response.isSuccessful) { "Upload failed: HTTP " + response.code + ". Check server upload support." } }
        val uploadMbps = payload.size * 8.0 / ((System.nanoTime() - uploadStart).coerceAtLeast(1) / 1_000_000_000.0) / 1_000_000.0
        onUpdate("Uploading", .95f, uploadMbps.toFloat())
        onUpdate("Complete", 1f, 0f); SpeedOutcome(downloadMbps, uploadMbps, ping, jitter)
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
    fun downloadAndInstall(context: Context, apkUrl: String): String = try {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Allow installs from SpeedTester, then tap Download and install again."
        }
        val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
        val response = client.newCall(Request.Builder().url(apkUrl).header("User-Agent", "SpeedTester-Android").build()).execute()
        val apkFile = File(context.cacheDir, "updates/SpeedTester-update.apk")
        apkFile.parentFile?.mkdirs()
        response.use { r -> check(r.isSuccessful) { "APK download failed: HTTP " + r.code }; val body = r.body ?: error("GitHub returned an empty APK"); body.byteStream().use { input -> apkFile.outputStream().use { output -> input.copyTo(output) } } }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apkFile)
        val install = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(install)
        "APK downloaded. Android's installer is ready."
    } catch (e: Exception) { "Update failed: " + (e.message ?: "network error") }
    private fun compareVersions(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }; val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until max(x.size, y.size)) { val d = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0); if (d != 0) return d }
        return 0
    }
}
