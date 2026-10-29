package com.speedtester.app

import java.util.Locale

internal enum class ServerSortMode(val label: String) {
    LOWEST_TCP_PING("Lowest TCP ping"),
    NEAREST("Nearest"),
    RECOMMENDED("Recommended")
}

/**
 * Recommended is a local, heuristic ranking of the observed TCP latency and approximate distance.
 * It does not claim to measure server throughput, server capacity, or provider quality.
 */
internal object ServerDirectoryLogic {
    fun matches(server: NdtServerCandidate, search: String): Boolean {
        val query = search.trim()
        return query.isBlank() || listOf(
            server.sponsor, server.city, server.host, server.serverId, server.label
        ).any { it.contains(query, ignoreCase = true) }
    }

    fun initials(sponsor: String): String {
        val meaningful = sponsor.trim().split(Regex("\\s+")).filter {
            it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("ltd", "limited", "inc", "company")
        }
        return meaningful.take(2).map { it.first().uppercaseChar() }.joinToString("").ifBlank { "ST" }
    }

    fun visible(servers: List<NdtServerCandidate>, query: String, mode: ServerSortMode): List<NdtServerCandidate> {
        val matches = servers.filter { matches(it, query) }
        val byLatency = compareBy<NdtServerCandidate> { it.latencyMs ?: Long.MAX_VALUE }
            .thenBy { it.distanceKm ?: Double.MAX_VALUE }.thenBy { it.serverId }.thenBy { it.host }
        val byDistance = compareBy<NdtServerCandidate> { it.distanceKm ?: Double.MAX_VALUE }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.serverId }.thenBy { it.host }
        return when (mode) {
            ServerSortMode.LOWEST_TCP_PING -> matches.sortedWith(byLatency)
            ServerSortMode.NEAREST -> matches.sortedWith(byDistance)
            ServerSortMode.RECOMMENDED -> {
                val latencyRank = matches.sortedWith(byLatency).mapIndexed { i, s -> s.host to i }.toMap()
                val distanceRank = matches.sortedWith(byDistance).mapIndexed { i, s -> s.host to i }.toMap()
                matches.sortedWith(compareBy<NdtServerCandidate> { candidate ->
                    val missingLatency = if (candidate.latencyMs == null) matches.size else 0
                    val missingDistance = if (candidate.distanceKm == null) matches.size else 0
                    (latencyRank[candidate.host] ?: matches.size) * 0.7 +
                        (distanceRank[candidate.host] ?: matches.size) * 0.3 +
                        missingLatency + missingDistance * 0.3
                }.thenBy { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.host })
            }
        }
    }
}
