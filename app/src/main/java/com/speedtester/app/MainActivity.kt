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
data class TestResult(val date: String, val download: Double, val upload: Double, val ping: Long, val jitter: Double, val server: String, val downloadAverage: Double = 0.0, val downloadPeak: Double = 0.0, val uploadAverage: Double = 0.0, val uploadPeak: Double = 0.0, val loadedPing: Long = -1L, val loadedJitter: Double = 0.0, val failedProbes: Int = 0, val probeCount: Int = 0)

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
    val activityLog = remember { mutableStateListOf("Ready. Start a test to see live diagnostics here.") }
    var netInfo by remember { mutableStateOf(readNetworkInfo(context)) }
    var loggedPhase by remember { mutableStateOf("") }
    var livePing by remember { mutableLongStateOf(-1L) }
    var liveJitter by remember { mutableDoubleStateOf(0.0) }
    val downloadSamples = remember { mutableStateListOf<Float>() }
    val uploadSamples = remember { mutableStateListOf<Float>() }
    val history = remember {
        runCatching {
            val arr = org.json.JSONArray(prefs.getString("history", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TestResult(o.optString("date"), o.optDouble("download"), o.optDouble("upload"), o.optLong("ping", -1), o.optDouble("jitter"), o.optString("server"), o.optDouble("downloadAverage"), o.optDouble("downloadPeak"), o.optDouble("uploadAverage"), o.optDouble("uploadPeak"), o.optLong("loadedPing", -1), o.optDouble("loadedJitter"), o.optInt("failedProbes"), o.optInt("probeCount"))
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
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(String.format(java.util.Locale.US, "%.1f", download), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White); Text("↓ DOWNLOAD", color = Color(0xFF32A8FF), style = MaterialTheme.typography.labelMedium); Text("Mbps", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(String.format(java.util.Locale.US, "%.1f", upload), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White); Text("↑ UPLOAD", color = Color(0xFFFFA64D), style = MaterialTheme.typography.labelMedium); Text("Mbps", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                    Column(Modifier.weight(0.7f), horizontalAlignment = Alignment.CenterHorizontally) { Text(if (livePing >= 0) livePing.toString() else if (ping >= 0) ping.toString() else "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White); Text("PING", color = Muted, style = MaterialTheme.typography.labelMedium); Text("ms", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                    Column(Modifier.weight(0.7f), horizontalAlignment = Alignment.CenterHorizontally) { Text(String.format(java.util.Locale.US, "%.1f", liveJitter.takeIf { it > 0 } ?: jitter), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White); Text("JITTER", color = Muted, style = MaterialTheme.typography.labelMedium); Text("ms", color = Muted, style = MaterialTheme.typography.labelSmall) }
                                }
                                Spacer(Modifier.height(10.dp))
                                BandwidthChart(downloadSamples.toList(), uploadSamples.toList(), Modifier.fillMaxWidth().height(170.dp))
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
                                        testing = true; error = null; progress = 0f; loggedPhase = ""; livePing = -1L; liveJitter = 0.0; downloadAverage = 0.0; downloadPeak = 0.0; uploadAverage = 0.0; uploadPeak = 0.0; loadedPing = -1L; loadedJitter = 0.0; failedProbes = 0; probeCount = 0; downloadSamples.clear(); uploadSamples.clear(); status = "Preparing test…"; activityLog.clear(); activityLog.add("Validating server URL"); activityLog.add("Preparing server connection and TCP latency probes"); activityLog.add("Target: " + endpoint.trim()); while (activityLog.size > 8) activityLog.removeAt(0)
                                        scope.launch {
                                            try {
                                                val result = SpeedEngine.run(endpoint.trim().trimEnd('/'), { phase, value, mbps ->
                                                    status = phase; progress = value
                                                    if (loggedPhase != phase) { loggedPhase = phase; activityLog.add(phase + " phase started"); while (activityLog.size > 8) activityLog.removeAt(0) }
                                                    if (mbps > 0f) { val isUpload = phase.contains("Upload", true); val target = if (isUpload) uploadSamples else downloadSamples; target.add(mbps); if (target.size > 80) target.removeAt(0); if (isUpload) { uploadAverage = target.average(); uploadPeak = (target.maxOrNull() ?: mbps).toDouble() } else { downloadAverage = target.average(); downloadPeak = (target.maxOrNull() ?: mbps).toDouble() }; val line = phase + " live rate: " + String.format(java.util.Locale.US, "%.1f Mbps", mbps); if (activityLog.lastOrNull()?.startsWith(phase + " live rate:") == true) activityLog[activityLog.lastIndex] = line else { activityLog.add(line); if (activityLog.size > 8) activityLog.removeAt(0) } }
                                                }, { p, j -> ping = p; jitter = j; livePing = p; liveJitter = j })
                                                download = result.download; upload = result.upload; ping = result.ping; jitter = result.jitter
                                                val saved = TestResult(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), download, upload, ping, jitter, endpoint, downloadAverage, downloadPeak, uploadAverage, uploadPeak, loadedPing, loadedJitter, failedProbes, probeCount)
                                                history.add(0, saved); while (history.size > 100) history.removeAt(history.lastIndex)
                                                val arr = org.json.JSONArray()
                                                history.forEach { x -> arr.put(JSONObject().put("date", x.date).put("download", x.download).put("upload", x.upload).put("ping", x.ping).put("jitter", x.jitter).put("server", x.server).put("downloadAverage", x.downloadAverage).put("downloadPeak", x.downloadPeak).put("uploadAverage", x.uploadAverage).put("uploadPeak", x.uploadPeak).put("loadedPing", x.loadedPing).put("loadedJitter", x.loadedJitter).put("failedProbes", x.failedProbes).put("probeCount", x.probeCount)) }
                                                prefs.edit().putString("history", arr.toString()).apply(); status = "Test complete"; activityLog.add("Test completed successfully"); activityLog.add("Download: " + String.format(java.util.Locale.US, "%.1f Mbps", download) + " · Upload: " + String.format(java.util.Locale.US, "%.1f Mbps", upload)); activityLog.add("Loaded TCP ping: " + (if (loadedPing >= 0) "$loadedPing ms" else "unavailable") + " · failed probes $failedProbes/$probeCount"); while (activityLog.size > 8) activityLog.removeAt(0)
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
                                InfoRow("Test server", runCatching { URL(endpoint).host }.getOrDefault("Invalid URL"))
                                 InfoRow("Protocol", runCatching { URL(endpoint).protocol.uppercase(java.util.Locale.ROOT) }.getOrDefault("—"))
                                 InfoRow("Network", netInfo.type); InfoRow("Internet capability", if (netInfo.connected) "Available" else "Unavailable")
                                InfoRow("Wi-Fi signal", netInfo.signal); InfoRow("Signal quality", netInfo.quality); InfoRow("Wi-Fi link speed", netInfo.link); InfoRow("Frequency / band", netInfo.frequency); InfoRow("Connection note", netInfo.note)
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
                                val csv = buildString { append("date,download_mbps,upload_mbps,download_average_mbps,download_peak_mbps,upload_average_mbps,upload_peak_mbps,ping_ms,jitter_ms,loaded_ping_ms,loaded_jitter_ms,failed_tcp_probes,total_tcp_probes,server\n"); history.forEach { val safeServer = it.server.replace("\"", "\"\""); append("\"" + it.date + "\"," + it.download + "," + it.upload + "," + it.downloadAverage + "," + it.downloadPeak + "," + it.uploadAverage + "," + it.uploadPeak + "," + it.ping + "," + it.jitter + "," + it.loadedPing + "," + it.loadedJitter + "," + it.failedProbes + "," + it.probeCount + ",\"" + safeServer + "\"\n") } }
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
                                Text("Avg / peak ↓ %.1f / %.1f · ↑ %.1f / %.1f Mbps".format(item.downloadAverage, item.downloadPeak, item.uploadAverage, item.uploadPeak), color = Muted, style = MaterialTheme.typography.bodySmall)
                                Text("Loaded ping " + (if (item.loadedPing >= 0) "${item.loadedPing} ms" else "—") + " · Failed probes ${item.failedProbes}/${item.probeCount}", color = Muted, style = MaterialTheme.typography.bodySmall)
                                Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { shareTestResult(context, item) }) { Text("Share result") } }
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
private data class SpeedOutcome(val download: Double, val upload: Double, val ping: Long, val jitter: Double, val downloadAverage: Double, val downloadPeak: Double, val uploadAverage: Double, val uploadPeak: Double, val loadedPing: Long, val loadedJitter: Double, val failedProbes: Int, val probeCount: Int)
private object SpeedEngine {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    suspend fun run(base: String, onUpdate: (String, Float, Float) -> Unit, onMetrics: (Long, Double) -> Unit): SpeedOutcome = withContext(Dispatchers.IO) {
        require(base.startsWith("https://") || base.startsWith("http://")) { "Server URL must start with HTTP or HTTPS" }
        val parsed = URL(base); val host = parsed.host; val port = if (parsed.port > 0) parsed.port else if (base.startsWith("https")) 443 else 80
        val pings = mutableListOf<Long>()
        repeat(10) {
            val start = System.nanoTime()
            Socket().use { it.connect(java.net.InetSocketAddress(host, port), 3000) }
            pings.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
            val avg = pings.average().toLong()
            val j = if (pings.size > 1) pings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average() else 0.0
            onMetrics(avg, j)
            onUpdate("Measuring latency", (.02f + .06f * pings.size / 10f), 0f)
            kotlinx.coroutines.delay(180)
        }
        val ping = pings.average().toLong()
        val jitter = if (pings.size > 1) pings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average() else 0.0
        onUpdate("Downloading", .08f, 0f)
        kotlinx.coroutines.delay(350)
        val cloudflare = URL(base).host.equals("speed.cloudflare.com", ignoreCase = true)
        val downloadUrl = if (cloudflare) base + "/__down?bytes=16000000&nocache=" + System.nanoTime() else base + "/garbage.php?cors=true&ckSize=8&nocache=" + System.nanoTime()
        val request = Request.Builder().url(downloadUrl).header("Cache-Control", "no-cache").build()
        val downloadRates = mutableListOf<Double>()
        val started = System.nanoTime(); var bytes = 0L; var lastReportNanos = started; var lastReportBytes = 0L
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP " + response.code + ". Check server URL." }
            val stream = response.body?.byteStream() ?: error("Empty download response"); val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = stream.read(buffer); if (n < 0) break; bytes += n
                val fraction = (bytes.toDouble() / 16_000_000.0).coerceIn(0.0, 1.0)
                val elapsed = (System.nanoTime() - started).coerceAtLeast(1) / 1_000_000_000.0
                val now = System.nanoTime()
                if (now - lastReportNanos >= 200_000_000L || bytes >= 8_000_000L) {
                    val windowSeconds = (now - lastReportNanos).coerceAtLeast(1) / 1_000_000_000.0
                    val liveMbps = ((bytes - lastReportBytes) * 8.0 / windowSeconds / 1_000_000.0).coerceAtLeast(0.0)
                    if (liveMbps > 0.0) downloadRates.add(liveMbps)
                    onUpdate("Downloading", (.08 + .42 * fraction).toFloat(), liveMbps.toFloat())
                    lastReportNanos = now; lastReportBytes = bytes
                }
            }
        }
        val downloadMbps = bytes * 8.0 / ((System.nanoTime() - started).coerceAtLeast(1) / 1_000_000_000.0) / 1_000_000.0
        if (downloadRates.isEmpty()) downloadRates.add(downloadMbps) else if (downloadRates.size == 1) downloadRates.add(downloadMbps)
        onUpdate("Uploading", .55f, 0f)
        val payload = ByteArray(4_000_000); java.security.SecureRandom().nextBytes(payload)
        val uploadUrl = if (cloudflare) base + "/__up?nocache=" + System.nanoTime() else base + "/empty.php?cors=true&nocache=" + System.nanoTime()
        kotlinx.coroutines.delay(350)
        val uploadStart = System.nanoTime()
        val uploadRates = mutableListOf<Double>()
        val uploadBody = object : okhttp3.RequestBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = payload.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                var offset = 0
                var lastReportNanos = uploadStart
                var lastReportBytes = 0
                while (offset < payload.size) {
                    val count = minOf(64 * 1024, payload.size - offset)
                    sink.write(payload, offset, count)
                    offset += count
                    val now = System.nanoTime()
                    if (now - lastReportNanos >= 200_000_000L || offset == payload.size) {
                        val windowSeconds = (now - lastReportNanos).coerceAtLeast(1) / 1_000_000_000.0
                        val liveMbps = ((offset - lastReportBytes) * 8.0 / windowSeconds / 1_000_000.0).coerceAtLeast(0.0)
                        if (liveMbps > 0.0) uploadRates.add(liveMbps)
                        onUpdate("Uploading", (.55 + .4 * offset / payload.size).toFloat(), liveMbps.toFloat())
                        lastReportNanos = now
                        lastReportBytes = offset
                    }
                }
            }
        }
        val uploadRequest = Request.Builder().url(uploadUrl).post(uploadBody).header("Cache-Control", "no-cache").build()
        client.newCall(uploadRequest).execute().use { response -> check(response.isSuccessful) { "Upload failed: HTTP " + response.code + ". Check server upload support." } }
        val uploadMbps = payload.size * 8.0 / ((System.nanoTime() - uploadStart).coerceAtLeast(1) / 1_000_000_000.0) / 1_000_000.0
        if (uploadRates.isEmpty()) uploadRates.add(uploadMbps) else if (uploadRates.size == 1) uploadRates.add(uploadMbps)
        onUpdate("Uploading", .95f, uploadMbps.toFloat())
        onUpdate("Checking connection stability", .96f, 0f)
        val loadedPings = mutableListOf<Long>()
        var failed = 0
        repeat(5) {
            try {
                val probeStart = System.nanoTime()
                Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1500) }
                loadedPings.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - probeStart))
            } catch (_: Exception) { failed++ }
            kotlinx.coroutines.delay(100)
        }
        val loadedPing = if (loadedPings.isNotEmpty()) loadedPings.average().toLong() else -1L
        val loadedJitter = if (loadedPings.size > 1) loadedPings.zipWithNext { a, b -> kotlin.math.abs(a - b).toDouble() }.average() else 0.0
        onUpdate("Complete", 1f, 0f)
        SpeedOutcome(downloadMbps, uploadMbps, ping, jitter, downloadRates.average(), downloadRates.maxOrNull() ?: downloadMbps, uploadRates.average(), uploadRates.maxOrNull() ?: uploadMbps, loadedPing, loadedJitter, failed, 5)
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
    fun downloadAndInstall(context: Context, apkUrl: String): String {
        return try {
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
    }
    private fun compareVersions(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }; val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until max(x.size, y.size)) { val d = (x.getOrNull(i) ?: 0).compareTo(y.getOrNull(i) ?: 0); if (d != 0) return d }
        return 0
    }
}
