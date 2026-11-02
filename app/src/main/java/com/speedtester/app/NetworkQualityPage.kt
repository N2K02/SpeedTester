package com.speedtester.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

/** A single, manual-only test page. Leaving the page disposes the scope and stops the test. */
@Composable
fun NetworkQualityPage(context: Context, modifier: Modifier = Modifier, onViewHistory: () -> Unit = {}) {
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
            access = runCatching { NetworkQualityRunner.access(context) }.getOrDefault(access)
            job = scope.launch {
                try {
                    val newReport = NetworkQualityRunner.run(context, profile) { name, done, total ->
                        stage = "$name · $done/$total"
                        progress = done.toFloat() / total.coerceAtLeast(1)
                    }
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
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("OVERALL CONNECTION QUALITY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(164.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { (score ?: 0).coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 10.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(score?.toString() ?: "—", style = MaterialTheme.typography.displayLarge,
                                fontWeight = FontWeight.Bold)
                            Text(if (score == null) "Not rated" else "/ 100",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(if (running) "Testing…" else if (report == null) "Ready to test"
                        else NetworkQualityLogic.label(score), style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(
                        if (running) stage else "Estimated TCP + HTTPS responsiveness · not a speed test",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (running) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = cancelTest, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel test")
                        }
                    } else {
                        Button(onClick = startTest, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (report == null) "Run network quality test" else "Test again")
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("NETWORK PATH", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        QualityPathStep(access.transport, access.transport != "Disconnected", Modifier.weight(1f))
                        // A detected gateway is not equivalent to a successful hop test.
                        QualityPathStep("Gateway", null, Modifier.weight(1f))
                        QualityPathStep("ISP", null, Modifier.weight(1f))
                        QualityPathStep("Internet",
                            report?.endpoints?.firstOrNull { it.service == "Internet" }?.responded,
                            Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    Text("Access: ${access.transport}" + (access.signalDbm?.let { " · $it dBm" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Gateway: ${if (access.gateway == null) "Not identified" else "Detected (not probed)"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Android internet validation: ${if (access.androidValidated) "Validated" else "Not validated"}",
                        style = MaterialTheme.typography.bodySmall)
                    if (access.frequencyMhz != null) {
                        Text("Wi-Fi band: ${QualityReportLogic.wifiBand(access.frequencyMhz)}", 
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Gateway and ISP hops are unmeasured; neutral icons do not mean a failure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("SERVICE ENDPOINTS", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Browsing", "Streaming", "Gaming", "WhatsApp").chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { name ->
                            val endpoint = report?.endpoints?.firstOrNull { it.service == name }
                            ElevatedCard(modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(when (name) {
                                            "Browsing" -> Icons.Default.Public
                                            "Streaming" -> Icons.Default.SmartDisplay
                                            "Gaming" -> Icons.Default.SportsEsports
                                            else -> Icons.Default.Chat
                                        }, contentDescription = null, modifier = Modifier.size(19.dp),
                                            tint = MaterialTheme.colorScheme.primary)
                                        Text(name, style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold)
                                    }
                                    Text(endpoint?.status ?: "Not tested",
                                        color = if (endpoint?.usable == true) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(endpoint?.latencyMs?.let { "$it ms HTTPS" } ?: "—",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            Text("These checks measure website endpoint reachability only, not actual video playback, gameplay or message delivery.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("MEASUREMENTS", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    QualityMetric("TCP connect median", report?.tcp?.latencyMs?.let { "$it ms" } ?: "—")
                    QualityMetric("TCP timing variation", report?.tcp?.jitterMs?.let { "%.1f ms".format(Locale.US, it) } ?: "—")
                    QualityMetric("TCP successes", report?.tcp?.let { "${it.successes}/${it.attempts}" } ?: "—")
                    QualityMetric("DNS resolution", report?.dnsLookupMs?.let { "$it ms" } ?: "Not measured")
                    QualityMetric("TLS handshake", report?.tlsHandshakeMs?.let { "$it ms" } ?: "Not measured")
                    QualityMetric("HTTPS responses", report?.endpoints?.let { endpoints ->
                        "${endpoints.sumOf { it.responses }}/${endpoints.sumOf { it.attempted }}"
                    } ?: "—")
                    QualityMetric("Test duration", report?.durationMs?.let { "%.1f s".format(Locale.US, it / 1000.0) } ?: "—")
                    QualityMetric("Process CPU time", report?.processCpuMs?.let { "$it ms" } ?: "—")
                    QualityMetric("App traffic (approx.)", report?.appTrafficBytes?.let {
                        "%.1f KiB".format(Locale.US, it / 1024.0)
                    } ?: "—")
                    Text("Traffic counter includes other SpeedTester activity during this interval.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Restricted website responses do not indicate packet loss or ISP failure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("TCP connection failures are not ICMP/UDP packet loss.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (report != null) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("WHAT WE FOUND", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        report!!.findings.forEach { finding ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Icon(when {
                                    finding.informational -> Icons.Default.Info
                                    finding.positive -> Icons.Default.CheckCircle
                                    else -> Icons.Default.Warning
                                }, contentDescription = null, modifier = Modifier.size(19.dp),
                                    tint = when {
                                        finding.informational -> MaterialTheme.colorScheme.onSurfaceVariant
                                        finding.positive -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.error
                                    })
                                Text(finding.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TEST PROFILE", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        QualityProfile.entries.forEach { option ->
                            FilterChip(selected = profile == option, onClick = {
                                profile = option
                                preferences.edit().putString("network_quality_profile", option.name).apply()
                            },
                                enabled = !running,
                                label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    Text("Only manual, low-data probes. Detailed adds samples; none performs a bandwidth or streaming stress test.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Press Run above to perform the selected profile. Results are also saved under Test History → Network Quality.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (failure != null && !running) item {
            Text(failure!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (report != null) item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SCENARIO CHECKS", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    QualityScenarioLogic.evaluate(report!!).forEach { scenario ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(scenario.title + " · " + scenario.state.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall)
                            Text(scenario.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityPathStep(label: String, healthy: Boolean?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = CircleShape,
            color = when (healthy) {
                true -> MaterialTheme.colorScheme.primaryContainer
                false -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surfaceContainerHighest
            }, modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(when (healthy) {
                    true -> Icons.Default.CheckCircle
                    false -> Icons.Default.Warning
                    null -> Icons.Default.Info
                }, contentDescription = null, modifier = Modifier.size(19.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            maxLines = 2)
    }
}

@Composable
private fun QualityMetric(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
