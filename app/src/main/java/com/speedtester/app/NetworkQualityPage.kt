package com.speedtester.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single, manual-only test page. Leaving the page disposes the scope and stops the test. */
@Composable
fun NetworkQualityPage(context: Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(QualityProfile.STANDARD) }
    var report by remember { mutableStateOf<QualityReport?>(null) }
    var access by remember { mutableStateOf(NetworkQualityRunner.access(context)) }
    var history by remember { mutableStateOf(NetworkQualityHistory.load(context)) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("Ready") }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }
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
                    Text(NetworkQualityLogic.label(score), style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(
                        if (running) stage else "Estimated TCP + HTTPS responsiveness · not a speed test",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
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
                        QualityPathStep("Gateway", access.gateway != null, Modifier.weight(1f))
                        QualityPathStep("Android check", access.androidValidated, Modifier.weight(1f))
                        QualityPathStep("HTTPS", report?.endpoints?.any { it.service == "Internet" && it.usable },
                            Modifier.weight(1f))
                    }
                    HorizontalDivider()
                    Text("Access: ${access.transport}" + (access.signalDbm?.let { " · $it dBm" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Gateway: ${if (access.gateway == null) "Not identified" else "Detected (not probed)"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Intermediate ISP and international routes are not individually measured.",
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
                                    Text(name, style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold)
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
                    QualityMetric("HTTPS successes", report?.endpoints?.let { endpoints ->
                        "${endpoints.sumOf { it.successful }}/${endpoints.sumOf { it.attempted }}"
                    } ?: "—")
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
                                Icon(if (finding.positive) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null, modifier = Modifier.size(19.dp),
                                    tint = if (finding.positive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error)
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
                            FilterChip(selected = profile == option, onClick = { profile = option },
                                enabled = !running,
                                label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    Text("Only manual, low-data probes. Detailed adds samples; none performs a bandwidth or streaming stress test.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    failure?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (running) {
                        OutlinedButton(onClick = { job?.cancel(); stage = "Cancelled" },
                            modifier = Modifier.fillMaxWidth()) { Text("Cancel test") }
                    } else {
                        Button(onClick = {
                            job = scope.launch {
                                running = true
                                progress = 0f
                                failure = null
                                stage = "Starting test"
                                try {
                                    val newReport = NetworkQualityRunner.run(context, profile) { name, done, total ->
                                        stage = "$name · $done/$total"
                                        progress = done.toFloat() / total.coerceAtLeast(1)
                                    }
                                    report = newReport
                                    access = newReport.access
                                    history = NetworkQualityHistory.save(context, newReport)
                                    stage = "Complete"
                                } catch (_: CancellationException) {
                                    stage = "Cancelled"
                                } catch (e: NetworkQualityRunner.NetworkChanged) {
                                    failure = e.message
                                } catch (e: Exception) {
                                    failure = "Unable to complete network test (${e.javaClass.simpleName})."
                                } finally {
                                    running = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.NetworkCheck, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (report == null) "Run network quality test" else "Test again")
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("RECENT RESULTS", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        history.take(5).forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider()
                            QualityMetric(
                                SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(entry.timestamp)) +
                                    " · " + entry.transport,
                                entry.score?.let { "$it/100" } ?: "—")
                        }
                        Text("Last 15 results saved locally without network identifiers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
