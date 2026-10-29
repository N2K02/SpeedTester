package com.speedtester.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Compact selectable cards; values are from the real server directory, never placeholders. */
@Composable
private fun ServerListCard(
    server: NdtServerCandidate, chosen: Boolean, loading: Boolean, onClick: () -> Unit
) {
    val selectionColor = MaterialTheme.colorScheme.primary
    Card(
        onClick = onClick,
        enabled = !loading,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (chosen) 2.dp else 1.dp,
            if (chosen) selectionColor else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = chosen, onClick = null, enabled = !loading,
                modifier = Modifier.size(38.dp))
            Spacer(Modifier.width(5.dp))
            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(ServerDirectoryLogic.initials(server.sponsor),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.sponsor.ifBlank { "Ookla server" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (server.uploadUrl.startsWith("https://", ignoreCase = true)) {
                        Spacer(Modifier.width(5.dp))
                        Surface(shape = RoundedCornerShape(9.dp),
                            color = Color(0xFF20C7B7).copy(alpha = 0.12f)) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, contentDescription = null,
                                    modifier = Modifier.size(11.dp), tint = Color(0xFF48DDC8))
                                Spacer(Modifier.width(3.dp))
                                Text("HTTPS", color = Color(0xFF48DDC8),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text(
                    server.city.ifBlank { "Unknown city" } + ", Mauritius · ID " +
                        server.serverId.ifBlank { "unknown" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(server.host, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Default.Place, contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(server.distanceKm?.let { "%.1f km".format(Locale.US, it) } ?: "Distance —",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Icon(Icons.Default.Bolt, contentDescription = null,
                        modifier = Modifier.size(14.dp), tint = selectionColor)
                    Text(server.latencyMs?.let { "$it ms TCP" } ?: "TCP unavailable",
                        style = MaterialTheme.typography.labelSmall,
                        color = selectionColor, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

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
    var sortMode by remember { mutableStateOf(ServerSortMode.LOWEST_TCP_PING) }
    val matched = remember(servers, search, sortMode) {
        ServerDirectoryLogic.visible(servers, search, sortMode)
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Router, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Mauritius Test Servers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text("Ookla directory · Mauritius only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onRefresh, enabled = !loading) {
                            Icon(Icons.Default.Refresh, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(3.dp))
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
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(22.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search provider, city, server ID…") }
            )
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerSortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sortMode == mode,
                        onClick = { sortMode = mode },
                        label = { Text(mode.label) },
                        leadingIcon = {
                            Icon(when (mode) {
                                ServerSortMode.LOWEST_TCP_PING -> Icons.Default.Tune
                                ServerSortMode.NEAREST -> Icons.Default.Place
                                ServerSortMode.RECOMMENDED -> Icons.Default.Star
                            }, contentDescription = null, modifier = Modifier.size(17.dp))
                        }
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(matched.size.toString() + " Mauritius servers",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                if (sortMode == ServerSortMode.RECOMMENDED) {
                    Text("Recommended combines observed TCP ping and approximate distance.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        if (error.isNotBlank()) item {
            Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (matched.isEmpty() && !loading) item {
            Text("No matching Mauritius servers. Try refreshing or adjusting your search.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }
        items(matched, key = { it.serverId.ifBlank { it.host } }) { server ->
            val chosen = if (server.serverId.isNotBlank() && selected?.serverId?.isNotBlank() == true)
                server.serverId == selected.serverId
            else server.host == selected?.host
            ServerListCard(server = server, chosen = chosen, loading = loading) {
                onSelect(server)
            }
        }
    }
}
