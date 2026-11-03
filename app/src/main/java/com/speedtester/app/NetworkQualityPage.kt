package com.speedtester.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Canvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

/** A single, manual-only test page. Leaving the page disposes the scope and stops the test. */
@Composable
fun NetworkQualityPage(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences("speedtester", Context.MODE_PRIVATE)
    }
    var profile by remember {
        mutableStateOf(QualityProfile.entries.firstOrNull {
            it.name == preferences.getString("network_quality_profile", QualityProfile.STANDARD.name)
        } ?: QualityProfile.STANDARD)
    }
    var report by remember { mutableStateOf<QualityReport?>(null) }
    // Explicit opt-in; real UHD playback may consume up to 24 MiB of mobile data.
    var realEnabled by remember {
        mutableStateOf(preferences.getBoolean("network_quality_real_enabled", false))
    }
    var realExpanded by remember { mutableStateOf(false) }
    var videoUrl by remember {
        mutableStateOf(preferences.getString("quality_video_url", RealProbeConfig.DEFAULT_VIDEO_URL)
            ?: RealProbeConfig.DEFAULT_VIDEO_URL)
    }
    var udpHost by remember { mutableStateOf(preferences.getString("quality_udp_host", "") ?: "") }
    var udpPort by remember { mutableStateOf(preferences.getString("quality_udp_port", "8788") ?: "8788") }
    var messageUrl by remember { mutableStateOf(preferences.getString("quality_wss_url", "") ?: "") }
    var messageRoom by remember { mutableStateOf("") }
    val videoPreview = remember(context) {
        PlayerView(context).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            keepScreenOn = false
        }
    }
    DisposableEffect(videoPreview) {
        onDispose { videoPreview.player = null }
    }
    var internetResponded by remember { mutableStateOf<Boolean?>(null) }
    var liveRealResults by remember { mutableStateOf<List<RealProbeResult>>(emptyList()) }
    var access by remember { mutableStateOf(NetworkQualityRunner.access(context)) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("Ready") }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    // React to network changes without a polling timer; release callbacks on page exit/Quit.
    DisposableEffect(context) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var trackedNetwork = connectivity.activeNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun invalidate() {
                scope.launch {
                    job?.cancel()
                    report = null
                    internetResponded = null
                    liveRealResults = emptyList()
                    access = runCatching { NetworkQualityRunner.access(context) }.getOrDefault(access)
                    failure = "Network changed. Run a new test."
                }
            }
            override fun onAvailable(network: Network) {
                if (network != trackedNetwork) {
                    trackedNetwork = network
                    invalidate()
                }
            }
            override fun onLost(network: Network) {
                if (network == trackedNetwork) {
                    trackedNetwork = null
                    invalidate()
                }
            }
        }
        val registered = runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)
        onDispose {
            if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
            job?.cancel()
        }
    }
    val startTest: () -> Unit = {
        if (!running) {
            // Set the flag before dispatching the coroutine so two fast taps cannot start parallel probes.
            running = true
            progress = 0f
            stage = "Starting test"
            failure = null
            report = null  // A different network may now be selected; never show a previous quality grade.
            internetResponded = null
            liveRealResults = emptyList()
            access = runCatching { NetworkQualityRunner.access(context) }.getOrDefault(access)
            job = scope.launch {
                try {
                    val newReport = NetworkQualityRunner.run(
                        context, profile,
                        onProgress = { name, done, total ->
                            stage = "$name · $done/$total"
                            progress = done.toFloat() / total.coerceAtLeast(1)
                        },
                        onInternetCheck = { observed -> internetResponded = observed },
                        onRealResult = { measured -> liveRealResults = liveRealResults + measured },
                        realConfig = RealProbeConfig(
                            enabled = realEnabled,
                            videoUrl = videoUrl.trim(),
                            udpHost = udpHost.trim(),
                            udpPort = udpPort.toIntOrNull() ?: 0,
                            messageUrl = messageUrl.trim(),
                            messageRoom = messageRoom.trim()
                        ),
                        videoPreview = if (realEnabled) videoPreview else null
                    )
                    report = newReport
                    access = newReport.access
                    withContext(Dispatchers.IO) { NetworkQualityHistory.save(context, newReport) }
                    stage = "Complete"
                } catch (_: CancellationException) {
                    stage = "Cancelled"
                } catch (e: NetworkQualityRunner.NetworkChanged) {
                    failure = e.message
                } catch (e: Exception) {
                    failure = "Unable to complete network test (${e.javaClass.simpleName})."
                } finally {
                    running = false
                    job = null
                }
            }
        }
    }
    val cancelTest: () -> Unit = {
        stage = "Cancelling…"
        job?.cancel()
    }
    // Keep manual probes from running after the app goes into the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) job?.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            job?.cancel()
        }
    }
    val score = report?.score
    val path = NetworkQualityPathLogic.build(
        access, report, running, stage, internetResponded, liveRealResults
    )
    val measuredEndpoints = report?.endpoints.orEmpty()
    val findings = report?.let { QualityScenarioLogic.evaluate(it) }.orEmpty()
    var expandedDiagnostics by remember { mutableStateOf(false) }

    // Preserve the shared mRadio-style Activity header. The test is manual-only.
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                QualityHeroCard(
                    score = score, progress = progress, running = running, stage = stage,
                    report = report, access = access
                )
            }
            item {
                QualityNetworkPathCard(path = path, running = running)
            }
            item {
                QualityServiceCard(measuredEndpoints, running, stage)
            }
            item {
                QualityMeasurementStrip(report)
            }
            item {
                Surface(
                    color = QualitySurface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, null, tint = QualityBlue, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Network info", color = Color.White,
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            QualityPill(
                                text = if (access.androidValidated) "Validated" else "Not validated",
                                tint = if (access.androidValidated) QualityGreen else QualityAmber
                            )
                        }
                        Text(
                            "${access.transport}" +
                                (access.frequencyMhz?.let { " · ${QualityReportLogic.wifiBand(it)}" } ?: "") +
                                (access.signalDbm?.let { " · $it dBm" } ?: "") +
                                " · Gateway ${if (access.gateway == null) "unknown" else "detected"}",
                            color = QualityMuted, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                Surface(
                    color = QualitySurface, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Test profile", style = MaterialTheme.typography.titleSmall,
                                color = Color.White, modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.SemiBold)
                            QualityPill(
                                text = if (running) "Testing" else "Manual only",
                                tint = if (running) QualityViolet else QualityBlue
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QualityProfile.entries.forEach { option ->
                                FilterChip(
                                    selected = profile == option,
                                    onClick = {
                                        profile = option
                                        preferences.edit().putString("network_quality_profile", option.name).apply()
                                    },
                                    enabled = !running,
                                    modifier = Modifier.weight(1f).height(39.dp),
                                    label = {
                                        Text(option.name.lowercase().replaceFirstChar { it.uppercase() },
                                            maxLines = 1, style = MaterialTheme.typography.labelSmall)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = QualityViolet.copy(alpha = .25f),
                                        selectedLabelColor = Color.White,
                                        containerColor = QualitySurfaceRaised,
                                        labelColor = QualityMuted
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = profile == option,
                                        selectedBorderColor = QualityViolet,
                                        borderColor = QualityLine
                                    )
                                )
                            }
                        }
                        Text("Small, bounded TCP and HTTPS checks. No video downloads or background stress tests.",
                            color = QualityMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                Surface(
                    color = QualitySurface, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Real-world diagnostics", color = Color.White,
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Real UHD playback · ICMP hops · UDP · receiver ACK · app traffic",
                                    color = QualityMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Switch(
                                checked = realEnabled,
                                onCheckedChange = {
                                    realEnabled = it
                                    preferences.edit().putBoolean("network_quality_real_enabled", it).apply()
                                },
                                enabled = !running
                            )
                        }
                        if (realEnabled) {
                            QualityPill("4K: max 24 MiB · max 15s · opt-in", QualityAmber)
                            Text(
                                "UDP game-action loss needs your relay. Confirmed message delivery " +
                                    "needs a separate receiver. No claim of third-party gameplay or WhatsApp delivery.",
                                color = QualityMuted, style = MaterialTheme.typography.labelSmall
                            )
                            TextButton(onClick = { realExpanded = !realExpanded }, enabled = !running,
                                contentPadding = PaddingValues(horizontal = 0.dp)) {
                                Text(if (realExpanded) "Hide relay setup" else "Configure optional UDP/message relay",
                                    style = MaterialTheme.typography.labelSmall, color = QualityVioletLight)
                                Icon(if (realExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null, tint = QualityVioletLight, modifier = Modifier.size(18.dp))
                            }
                            if (realExpanded) {
                                OutlinedTextField(value = videoUrl, onValueChange = {
                                    videoUrl = it.take(512)
                                    preferences.edit().putString("quality_video_url", videoUrl).apply()
                                }, label = { Text("4K video HTTPS URL") }, singleLine = true,
                                    enabled = !running, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = udpHost, onValueChange = {
                                    udpHost = it.take(120)
                                    preferences.edit().putString("quality_udp_host", udpHost).apply()
                                }, label = { Text("UDP relay hostname") }, singleLine = true,
                                    enabled = !running, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = udpPort, onValueChange = {
                                    udpPort = it.filter(Char::isDigit).take(5)
                                    preferences.edit().putString("quality_udp_port", udpPort).apply()
                                }, label = { Text("UDP port") }, singleLine = true,
                                    enabled = !running, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = messageUrl, onValueChange = {
                                    messageUrl = it.take(240)
                                    preferences.edit().putString("quality_wss_url", messageUrl).apply()
                                }, label = { Text("Secure message relay (wss://.../ws)") },
                                    singleLine = true, enabled = !running,
                                    modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = messageRoom, onValueChange = {
                                    messageRoom = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(64)
                                }, label = { Text("Shared 12+ character pairing code") },
                                    singleLine = true, enabled = !running,
                                    modifier = Modifier.fillMaxWidth())
                                Text("Run the receiver.mjs example from the quality-relay folder " +
                                    "on another device before starting. Pairing codes are not saved in history.",
                                    style = MaterialTheme.typography.labelSmall, color = QualityMuted)
                            }
                        }
                    }
                }
            }
            if (report?.realTests?.isNotEmpty() == true) item {
                Surface(color = QualitySurface, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("REAL-WORLD RESULTS", color = Color.White,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        report!!.realTests.forEach { result ->
                            val tint = when (result.state) {
                                RealProbeState.VERIFIED -> QualityGreen
                                RealProbeState.DEGRADED -> QualityAmber
                                RealProbeState.UNAVAILABLE -> QualityRed
                                RealProbeState.NOT_CONFIGURED -> QualityMuted
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(result.title, color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.weight(1f))
                                    QualityPill(result.state.name.replace('_', ' '), tint)
                                }
                                Text(result.detail, color = QualityMuted,
                                    style = MaterialTheme.typography.labelSmall)
                                if (result.rxBytes != null || result.txBytes != null) {
                                    Text("App RX ${result.rxBytes ?: 0} B · TX ${result.txBytes ?: 0} B (approximate)",
                                        color = QualityCyan, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (report != null) item {
                Surface(
                    color = QualitySurface, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Scenario diagnostics", style = MaterialTheme.typography.titleSmall,
                                color = Color.White, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { expandedDiagnostics = !expandedDiagnostics },
                                contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text(if (expandedDiagnostics) "Less" else "All ${findings.size}",
                                    color = QualityVioletLight, style = MaterialTheme.typography.labelSmall)
                                Icon(if (expandedDiagnostics) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null, modifier = Modifier.size(18.dp), tint = QualityVioletLight)
                            }
                        }
                        val relevant = if (expandedDiagnostics) findings else
                            findings.filter {
                                it.state == QualityScenarioState.CAUTION ||
                                    it.state == QualityScenarioState.UNKNOWN
                            }.take(3).ifEmpty { findings.take(2) }
                        relevant.forEach { scenario ->
                            QualityScenarioRow(scenario)
                        }
                        if (!expandedDiagnostics) {
                            Text("More measured scenarios are available in Test History → Network Quality.",
                                color = QualityMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (failure != null && !running) item {
                Surface(color = QualityRed.copy(alpha = .13f), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = QualityRed, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(failure!!, color = QualityRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text(
                    "Website checks test HTTPS reachability, not video playback, gameplay or message delivery. " +
                        "TCP connection failures are not ICMP/UDP packet loss. Optional real checks probe gateway ICMP and only TTL-limited early route hops; they do not map the full ISP path. " +
                        "App traffic includes other SpeedTester activity during the test.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)
                )
            }
        }

        // Exactly one primary action, pinned so it is reachable without scrolling.
        Surface(shadowElevation = 5.dp, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (running && realEnabled) {
                    Text("Real UHD playback preview · muted · no streaming in background",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AndroidView(
                        factory = { videoPreview },
                        modifier = Modifier.fillMaxWidth().height(112.dp)
                    )
                    Spacer(Modifier.height(5.dp))
                }
                if (running) {
                    LinearProgressIndicator(progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = QualityViolet, trackColor = QualityLine)
                    Spacer(Modifier.height(6.dp))
                }
                Button(
                    onClick = { if (running) cancelTest() else startTest() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) QualitySurfaceRaised else QualityViolet,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(17.dp)
                ) {
                    Icon(if (running) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Cancel network test" else "Run network test",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private val QualitySurface = Color(0xFF14223B)
private val QualitySurfaceRaised = Color(0xFF253453)
private val QualityLine = Color(0xFF405071)
private val QualityMuted = Color(0xFFB7C4DF)
private val QualityGreen = Color(0xFF3EF3A2)
private val QualityAmber = Color(0xFFFFD36B)
private val QualityRed = Color(0xFFFF707C)
private val QualityViolet = Color(0xFF7254EF)
private val QualityVioletLight = Color(0xFFC0B5FF)
private val QualityBlue = Color(0xFF6EDBFF)
private val QualityCyan = Color(0xFF54EEE4)

@Composable
private fun QualityPill(text: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = tint.copy(alpha = .16f),
        shape = RoundedCornerShape(40.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun QualityHeroCard(
    score: Int?,
    progress: Float,
    running: Boolean,
    stage: String,
    report: QualityReport?,
    access: QualityAccess
) {
    val ratingColor = when {
        score == null -> QualityVioletLight
        score >= 75 -> QualityGreen
        score >= 40 -> QualityAmber
        else -> QualityRed
    }
    Box(
        modifier = Modifier.fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF08566A), Color(0xFF0F394A), Color(0xFF093D39))
                ),
                shape = RoundedCornerShape(21.dp)
            )
            .padding(15.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (running) progress else (score ?: 0) / 100f },
                        color = ratingColor,
                        trackColor = Color.White.copy(alpha = .18f),
                        strokeWidth = 9.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text(if (score == null) "—" else "$score",
                            color = Color.White, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineLarge)
                        Text(if (score == null) "NOT RATED" else "/ 100",
                            color = QualityMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Network Quality", color = Color.White,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    QualityPill(
                        when {
                            running -> "TESTING"
                            report == null -> "READY"
                            score == null -> "NOT RATED"
                            else -> NetworkQualityLogic.label(score)
                        }, ratingColor
                    )
                    Text(when {
                        running -> stage
                        report == null -> "Run a test to assess connection responsiveness"
                        score == null -> "No sufficient HTTPS data to assign a score"
                        else -> "TCP + HTTPS responsiveness estimate"
                    }, color = QualityMuted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(access.transport + (access.frequencyMhz?.let {
                        " · " + QualityReportLogic.wifiBand(it)
                    } ?: ""), color = Color.White.copy(alpha = .85f),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (report != null) {
                val successes = report.endpoints.sumOf { it.responses }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QualityPill("${report.endpoints.sumOf { it.responses }}/${report.endpoints.sumOf { it.attempted }} HTTPS",
                        if (successes > 0) QualityGreen else QualityRed)
                    QualityPill("${report.tcp.successes}/${report.tcp.attempts} TCP",
                        if (report.tcp.successes == report.tcp.attempts) QualityGreen else QualityAmber)
                }
            } else if (running) {
                Text("Checking real connection stages · ${(progress * 100).toInt().coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelSmall, color = QualityMuted)
            }
        }
    }
}

private fun pathTint(state: QualityPathState): Color = when (state) {
    QualityPathState.SUCCESS -> QualityGreen
    QualityPathState.FAILED -> QualityRed
    QualityPathState.TESTING -> QualityVioletLight
    QualityPathState.PENDING -> QualityMuted
    QualityPathState.UNMEASURED -> QualityAmber
}

@Composable
private fun QualityNetworkPathCard(path: List<QualityPathNode>, running: Boolean) {
    Surface(color = QualitySurface, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(19.dp), tint = QualityBlue)
                Spacer(Modifier.width(7.dp))
                Text("Network path", color = Color.White,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                QualityPill(
                    text = if (running) "CHECKING" else if (path.last().state == QualityPathState.SUCCESS)
                        "CONNECTED" else if (path.last().state == QualityPathState.FAILED) "NO REPLY" else "READY",
                    tint = pathTint(path.last().state)
                )
            }
            Box(Modifier.fillMaxWidth().height(83.dp)) {
                Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                    val count = path.size
                    val first = size.width / count / 2f
                    val dx = size.width / count
                    val y = 20.dp.toPx()
                    val circleEdge = 18.dp.toPx()
                    for (index in 0 until count - 1) {
                        val from = path[index]
                        val to = path[index + 1]
                        val tint = when {
                            to.state == QualityPathState.FAILED -> QualityRed
                            to.state == QualityPathState.TESTING -> QualityVioletLight
                            from.state == QualityPathState.SUCCESS && to.state == QualityPathState.SUCCESS -> QualityGreen
                            else -> QualityLine
                        }
                        drawLine(color = tint,
                            start = Offset(first + index * dx + circleEdge, y),
                            end = Offset(first + (index + 1) * dx - circleEdge, y),
                            strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    path.forEach { node ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(38.dp).background(
                                    QualitySurfaceRaised, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(
                                        when (node.id) {
                                            "device" -> Icons.Default.Smartphone
                                            "link" -> Icons.Default.Wifi
                                            "gateway" -> Icons.Default.Router
                                            "isp" -> Icons.Default.Public
                                            else -> Icons.Default.Cloud
                                        },
                                        contentDescription = null, modifier = Modifier.size(21.dp),
                                        tint = Color.White
                                    )
                                }
                                Box(Modifier.align(Alignment.BottomEnd).size(16.dp).background(
                                    pathTint(node.state), CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    Icon(
                                        when (node.state) {
                                            QualityPathState.SUCCESS -> Icons.Default.CheckCircle
                                            QualityPathState.FAILED -> Icons.Default.Close
                                            QualityPathState.TESTING -> Icons.Default.NetworkCheck
                                            else -> Icons.Default.Info
                                        },
                                        contentDescription = node.state.name,
                                        tint = QualitySurface, modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                            Text(node.title, style = MaterialTheme.typography.labelSmall,
                                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(node.detail, style = MaterialTheme.typography.labelSmall,
                                color = pathTint(node.state),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text("Green: observed · Red: failed · Purple: testing · Amber: not probed",
                color = QualityMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun QualityServiceCard(
    endpoints: List<QualityEndpoint>,
    running: Boolean,
    stage: String
) {
    val services = listOf(
        Triple("Browsing", Icons.Default.Public, QualityGreen),
        Triple("Streaming", Icons.Default.SmartDisplay, QualityVioletLight),
        Triple("Gaming", Icons.Default.SportsEsports, QualityAmber),
        Triple("WhatsApp", Icons.Default.Chat, QualityCyan)
    )
    Surface(color = QualitySurface, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Test scenarios", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.weight(1f))
                QualityPill("HTTPS endpoints", QualityBlue)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                services.forEach { (name, icon, tint) ->
                    val endpoint = endpoints.firstOrNull { it.service == name }
                    val testing = running && stage.startsWith(name)
                    val text = when {
                        endpoint?.usable == true -> "Reachable"
                        endpoint?.responded == true -> "HTTP"
                        endpoint != null -> "Fail"
                        testing -> "Testing"
                        else -> "—"
                    }
                    val stateColor = when {
                        endpoint?.usable == true -> QualityGreen
                        endpoint?.responded == true -> QualityAmber
                        endpoint != null -> QualityRed
                        testing -> QualityVioletLight
                        else -> QualityMuted
                    }
                    Surface(
                        color = tint.copy(alpha = .12f), shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(icon, contentDescription = null, tint = tint,
                                modifier = Modifier.size(22.dp))
                            Text(if (name == "WhatsApp") "Chat" else name,
                                color = Color.White, style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(endpoint?.latencyMs?.let { "$it ms" } ?: "—",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold, color = Color.White)
                            QualityPill(text, stateColor)
                        }
                    }
                }
            }
            Text("Website reachability only; not actual video playback, gameplay or message delivery.",
                color = QualityMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun QualityMeasurementStrip(report: QualityReport?) {
    val metrics = listOf(
        Triple("TCP", report?.tcp?.latencyMs?.let { "$it ms" } ?: "—", QualityBlue),
        Triple("Variation", report?.tcp?.jitterMs?.let {
            "%.1f ms".format(Locale.US, it)
        } ?: "—", QualityVioletLight),
        Triple("DNS", report?.dnsLookupMs?.let { "$it ms" } ?: "—", QualityCyan),
        Triple("TLS", report?.tlsHandshakeMs?.let { "$it ms" } ?: "—", QualityAmber)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        metrics.forEach { (label, value, tint) ->
            Surface(
                color = QualitySurface, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Column(Modifier.padding(horizontal = 5.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint,
                        maxLines = 1)
                    Text(value, style = MaterialTheme.typography.titleSmall, color = Color.White,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
    if (report != null) {
        Spacer(Modifier.height(3.dp))
        Text(
            "${report.tcp.successes}/${report.tcp.attempts} TCP · " +
                "${report.endpoints.sumOf { it.responses }}/${report.endpoints.sumOf { it.attempted }} HTTPS · " +
                "${(report.durationMs / 1000f).let { "%.1f".format(Locale.US, it) }}s · " +
                "${report.processCpuMs?.let { "$it ms CPU" } ?: "CPU unavailable"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp)
        )
    }
}

@Composable
private fun QualityScenarioRow(scenario: QualityScenarioResult) {
    val tint = when (scenario.state) {
        QualityScenarioState.GOOD -> QualityGreen
        QualityScenarioState.CAUTION -> QualityRed
        QualityScenarioState.INFORMATIONAL -> QualityAmber
        QualityScenarioState.UNKNOWN -> QualityMuted
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            when (scenario.state) {
                QualityScenarioState.GOOD -> Icons.Default.CheckCircleOutline
                QualityScenarioState.CAUTION -> Icons.Default.ErrorOutline
                else -> Icons.Default.Info
            }, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(scenario.title, color = Color.White, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold)
            Text(scenario.detail, color = QualityMuted, style = MaterialTheme.typography.labelSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        QualityPill(scenario.state.name.lowercase().replaceFirstChar { it.uppercase() }, tint)
    }
}
