package com.speedtester.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SpeedServerDirectory(
    servers: List<NdtServerCandidate>,
    selected: NdtServerCandidate?,
    loading: Boolean,
    error: String,
    onRefresh: () -> Unit,
    onSelect: (NdtServerCandidate) -> Unit,
    modifier: Modifier = Modifier
) {
    var search by remember { mutableStateOf("") }
    var sortByLatency by remember { mutableStateOf(true) }
    val matched = servers.filter {
        listOf(it.label, it.host, it.city, it.serverId)
            .any { part -> part.contains(search.trim(), ignoreCase = true) }
    }.let { list ->
        if (sortByLatency) list.sortedBy { it.latencyMs ?: Long.MAX_VALUE }
        else list.sortedBy { it.distanceKm ?: Double.MAX_VALUE }
    }
    LazyColumn(modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Router, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Mauritius Test Servers", fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium)
                            Text("Ookla directory · Mauritius only", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onRefresh, enabled = !loading) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                    Text("Choose a server for your next test. Distances are approximate and TCP latency is measured separately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedTextField(value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search provider, city, server ID") })
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(matched.size.toString() + " Mauritius servers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { sortByLatency = !sortByLatency }) {
                    Text("Sort: " + if (sortByLatency) "Lowest TCP ping" else "Nearest")
                }
            }
        }
        if (loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        if (error.isNotBlank()) item {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (matched.isEmpty() && !loading) item {
            Text("No matching Mauritius servers. Try refreshing or changing the search.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(matched, key = { it.serverId.ifBlank { it.host } }) { server ->
            val chosen = server.host == selected?.host
            Card(onClick = { onSelect(server) }, enabled = !loading,
                shape = RoundedCornerShape(17.dp),
                border = BorderStroke(if (chosen) 2.dp else 1.dp,
                    if (chosen) Color(0xFF3091FF) else MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor =
                    if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen, onClick = { onSelect(server) }, enabled = !loading)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(server.sponsor.ifBlank { "Ookla server" } + " · " + server.city,
                            fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(server.host, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(server.city + ", Mauritius · ID " + server.serverId.ifBlank { "unknown" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(server.distanceKm?.let { "%.1f km".format(java.util.Locale.US, it) } ?: "Distance unknown",
                                style = MaterialTheme.typography.labelSmall)
                            Text(server.latencyMs?.let { "$it ms TCP" } ?: "TCP ping unavailable",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
