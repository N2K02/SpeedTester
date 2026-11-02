package com.speedtester.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NetworkQualityPage(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<QualitySample?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Network Quality", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Lightweight connection responsiveness test", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result?.grade?.toString() ?: "—", style = MaterialTheme.typography.displayLarge)
                    Text(NetworkQualityLogic.label(result?.grade), style = MaterialTheme.typography.titleLarge)
                    Text("Estimated TCP responsiveness score · not an internet speed score")
                }
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connection diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text("TCP connect median: ${result?.latencyMs?.let { "$it ms" } ?: "—"}")
                    Text("TCP timing variation: ${result?.jitterMs?.let { "%.1f ms".format(it) } ?: "—"}")
                    Text("Successful attempts: ${result?.let { "${it.successes}/${it.attempts}" } ?: "—"}")
                    Text(result?.note ?: "Run a test to see measured results.")
                }
            }
        }
        item {
            Text("Service quality", style = MaterialTheme.typography.titleMedium)
            Text("Browsing, streaming, gaming and messaging ratings require dedicated endpoint tests; no ratings are inferred from TCP timings.")
        }
        item {
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !running, onClick = {
                scope.launch {
                    running = true
                    failure = null
                    try { result = NetworkQualityProbe.run() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = "Test unavailable: ${e.javaClass.simpleName}" }
                    finally { running = false }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Testing…" else "Run network quality test")
            }
        }
    }
}
