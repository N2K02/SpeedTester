package com.speedtester.app

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Test History: swipe left/right or tap a tab to change between speed and quality results. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpeedTestHistoryTabbedScreen(
    context: Context,
    history: List<TestResult>,
    export: () -> Unit,
    clear: () -> Unit,
    open: (TestResult) -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 0
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, 1), pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            listOf("Speed tests", "Network Quality").forEachIndexed { page, label ->
                Tab(
                    selected = pagerState.currentPage == page,
                    onClick = { scope.launch { pagerState.animateScrollToPage(page) } },
                    text = { Text(label) }
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            if (page == 0) {
                SpeedTestHistoryScreen(
                    history = history, export = export, clear = clear, open = open,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                NetworkQualityHistoryPage(context = context, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun NetworkQualityHistoryPage(context: Context, modifier: Modifier = Modifier) {
    var results by remember(context) { mutableStateOf(NetworkQualityHistory.load(context)) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Long?>(null) }
    LazyColumn(modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Network Quality history", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("${results.size} saved diagnostics · detailed scenarios",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { confirmClear = true }, enabled = results.isNotEmpty()) {
                    Text("Clear")
                }
            }
        }
        if (results.isEmpty()) item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.History, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text("No Network Quality results yet", style = MaterialTheme.typography.titleMedium)
                    Text("Open the Quality menu and run a diagnostic. Results are saved here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(results, key = { it.timestamp }) { item ->
            ElevatedCard(
                onClick = { expanded = if (expanded == item.timestamp) null else item.timestamp },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())
                                    .format(Date(item.timestamp)),
                                style = MaterialTheme.typography.titleSmall)
                            Text("${item.transport} · ${item.profile.lowercase().replaceFirstChar { it.uppercase() }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(item.score?.let { "$it / 100" } ?: "Unrated",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Text("TCP: ${item.tcpMs?.let { "$it ms" } ?: "—"}  ·  DNS: ${item.dnsMs?.let { "$it ms" } ?: "—"}  ·  TLS: ${item.tlsMs?.let { "$it ms" } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("HTTPS: ${item.httpsResponses}/${item.httpsAttempts} replies · Tap to ${if (expanded == item.timestamp) "collapse" else "view"} details",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expanded == item.timestamp) {
                        HorizontalDivider()
                        item.jitterMs?.let {
                            Text("TCP timing variation: %.1f ms".format(Locale.US, it),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        item.durationMs?.let {
                            Text("Duration: %.1f seconds".format(Locale.US, it / 1000.0),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        item.cpuMs?.let {
                            Text("Process CPU time: $it ms", style = MaterialTheme.typography.bodySmall)
                        }
                        item.trafficBytes?.let {
                            Text("Approximate app traffic: %.1f KiB".format(Locale.US, it / 1024.0),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (item.scenarios.isEmpty()) {
                            Text("Detailed scenarios were not saved for this older result.",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            item.scenarios.forEach { scenario ->
                                QualityHistoryScenarioRow(scenario)
                            }
                        }
                        Text("Endpoint checks do not measure gameplay, video playback or WhatsApp delivery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { deleteTarget = item.timestamp },
                            modifier = Modifier.align(Alignment.End)) {
                            Text("Delete result")
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear Network Quality history?") },
        text = { Text("Delete all ${results.size} locally stored Quality results? Speed-test history remains untouched.") },
        confirmButton = {
            TextButton(onClick = {
                NetworkQualityHistory.clear(context)
                results = emptyList()
                expanded = null
                confirmClear = false
            }) { Text("Clear quality results") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
    deleteTarget?.let { timestamp ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this Quality result?") },
            text = { Text("This saved diagnostic will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    results = NetworkQualityHistory.delete(context, timestamp)
                    expanded = null
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun QualityHistoryScenarioRow(scenario: QualityScenarioResult) {
    val (icon, tint) = when (scenario.state) {
        QualityScenarioState.GOOD -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        QualityScenarioState.CAUTION -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
        QualityScenarioState.INFORMATIONAL -> Icons.Default.Info to MaterialTheme.colorScheme.onSurfaceVariant
        QualityScenarioState.UNKNOWN -> Icons.Default.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top) {
        Icon(icon, null, modifier = Modifier.size(19.dp), tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(scenario.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(scenario.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
