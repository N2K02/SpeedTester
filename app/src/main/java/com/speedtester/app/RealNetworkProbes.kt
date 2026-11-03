package com.speedtester.app

import android.net.Network
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** All scenarios use real network requests. If probing is blocked, report unavailable, not green. */
object RealNetworkProbes {
    private fun uidTraffic(): Pair<Long, Long>? {
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        val tx = TrafficStats.getUidTxBytes(Process.myUid())
        return if (rx < 0 || tx < 0) null else rx to tx
    }

    /** Includes other traffic from the same app during the measured interval. */
    suspend fun measure(block: suspend () -> RealProbeResult): RealProbeResult {
        val start = uidTraffic()
        val result = block()
        val end = uidTraffic()
        val rx = if (start != null && end != null && end.first >= start.first)
            end.first - start.first else null
        val tx = if (start != null && end != null && end.second >= start.second)
            end.second - start.second else null
        return result.copy(rxBytes = rx, txBytes = tx)
    }

    private data class PingResult(val replied: Boolean, val ttlExceeded: Boolean, val output: String)

    /** Android ping is best-effort: vendors may remove it or deny ICMP. */
    private suspend fun ping(args: List<String>): PingResult? = withContext(Dispatchers.IO) {
        var process: java.lang.Process? = null
        try {
            withTimeoutOrNull(2_600L) {
                val command = listOf("ping", "-n", "-c", "1", "-W", "1") + args
                process = ProcessBuilder(command).redirectErrorStream(true).start()
                val active = process ?: return@withTimeoutOrNull null
                val output = active.inputStream.bufferedReader().use { it.readText().take(4_096) }
                active.waitFor()
                val response = Regex("""(?i)(?:\d+\s+bytes\s+from|1\s+(?:packets\s+)?received|1\s+received)""")
                    .containsMatchIn(output)
                val exceeded = Regex("""(?i)(?:time\s+to\s+live\s+exceeded|ttl\s+exceeded)""")
                    .containsMatchIn(output)
                PingResult(response && !exceeded, exceeded, output)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            process?.destroyForcibly()
        }
    }

    suspend fun gateway(gatewayIp: String?): RealProbeResult {
        val ip = gatewayIp?.takeIf { it.isNotEmpty() &&
            Regex("[0-9a-fA-F:.]{2,60}").matches(it) }
            ?: return RealProbeResult("gateway", "Gateway ICMP",
                RealProbeState.UNAVAILABLE, "No valid gateway address or route is available.")
        val response = ping(listOf(ip)) ?: return RealProbeResult("gateway", "Gateway ICMP",
            RealProbeState.UNAVAILABLE, "ICMP ping utility unavailable or blocked on this Android device.")
        return RealProbeResult("gateway", "Gateway ICMP",
            if (response.replied) RealProbeState.VERIFIED else RealProbeState.UNAVAILABLE,
            if (response.replied) "The default gateway replied to a real ICMP echo probe."
            else "No gateway ICMP reply; firewall filtering is possible. Reachability is not disproven.")
    }

    suspend fun route(): RealProbeResult {
        var replied = 0
        var expired = 0
        var unsupported = 0
        for (ttl in 1..3) {
            currentCoroutineContext().ensureActive()
            val result = ping(listOf("-t", ttl.toString(), "1.1.1.1"))
            if (result == null) unsupported++
            else if (result.replied) replied++
            else if (result.ttlExceeded) expired++
        }
        val measured = replied + expired
        return RealProbeResult("isp", "ISP route (TTL 1–3)",
            if (measured > 0) RealProbeState.VERIFIED else RealProbeState.UNAVAILABLE,
            if (measured > 0)
                "${measured}/3 TTL-limited ICMP probes produced a response " +
                    "(${expired} TTL-expired, ${replied} destination); no claim of a complete ISP route."
            else "TTL-limited ICMP probes received no verifiable hop replies. " +
                "Router filtering or unsupported ping options are possible (${unsupported} unsupported probes).")
    }

    suspend fun udp(network: Network, host: String, port: Int): RealProbeResult {
        if (host.isBlank() || port !in 1..65535) return RealProbeResult(
            "game", "UDP game-action round trip", RealProbeState.NOT_CONFIGURED,
            "A cooperating UDP echo relay is required. No synthetic loss percentage is shown."
        )
        return withContext(Dispatchers.IO) {
            val address = runCatching { network.getAllByName(host.trim()).firstOrNull() }.getOrNull()
                ?: return@withContext RealProbeResult("game", "UDP game-action round trip",
                    RealProbeState.UNAVAILABLE, "The relay hostname could not be resolved on this network.")
            val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
            val times = mutableListOf<Long>()
            var sent = 0
            var replies = 0
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = false
                    network.bindSocket(socket)
                    socket.bind(java.net.InetSocketAddress(0))
                    socket.soTimeout = 650
                    repeat(12) { seq ->
                        currentCoroutineContext().ensureActive()
                        val bytes = "STQ1|$nonce|$seq".toByteArray(Charsets.US_ASCII)
                        val start = SystemClock.elapsedRealtimeNanos()
                        socket.send(DatagramPacket(bytes, bytes.size, address, port))
                        sent++
                        // Treat only a matching nonce and sequence from the configured relay as a reply.
                        val buffer = ByteArray(128)
                        val incoming = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(incoming)
                            val echo = String(incoming.data, incoming.offset, incoming.length, Charsets.US_ASCII)
                            if (incoming.address == address && incoming.port == port &&
                                echo == String(bytes, Charsets.US_ASCII)) {
                                replies++
                                times += ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
                                    .coerceAtLeast(1)
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Missing a response is meaningful only after at least one authenticated echo.
                        }
                    }
                }
                RealProbeAnalysis.udp(sent, replies, times)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RealProbeResult("game", "UDP game-action round trip", RealProbeState.UNAVAILABLE,
                    "The UDP relay was unreachable or the selected network blocked UDP. No loss inferred.")
            }
        }
    }

    /**
     * This tests actual ACK from a DIFFERENT connected client, never an echo from the server.
     * Room codes are user-chosen, used only for this session and never persisted in test history.
     */
    suspend fun messageRelay(url: String, room: String, client: OkHttpClient): RealProbeResult {
        val normalized = runCatching { URI(url.trim()) }.getOrNull()
        if (normalized?.scheme != "wss" || normalized.host.isNullOrBlank() ||
            room.length !in 12..64 || !Regex("[A-Za-z0-9_-]+").matches(room)) {
            return RealProbeResult("message", "Two-device message delivery",
                RealProbeState.NOT_CONFIGURED,
                "Configure a TLS WebSocket relay (wss://) and a 12–64 character pairing code.")
        }
        val completed = CompletableDeferred<Unit>()
        val pending = ConcurrentHashMap<String, Long>()
        val batchSent = java.util.concurrent.atomic.AtomicBoolean(false)
        val delays = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val sent = 5
        var delivered = 0
        var socket: WebSocket? = null
        return try {
            val encodedRoom = URLEncoder.encode(room, "UTF-8")
            val join = url.trim() + (if (url.contains("?")) "&" else "?") +
                "room=$encodedRoom&role=sender"
            val request = Request.Builder().url(join).build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (obj.optString("type")) {
                        "ready" -> if (obj.optInt("receivers", 0) > 0 && batchSent.compareAndSet(false, true)) {
                            repeat(sent) {
                                val id = UUID.randomUUID().toString()
                                pending[id] = SystemClock.elapsedRealtimeNanos()
                                webSocket.send(JSONObject().apply {
                                    put("type", "message")
                                    put("id", id)
                                    put("payload", "speedtester-test-$it")
                                }.toString())
                            }
                        }
                        "delivered" -> {
                            val started = pending.remove(obj.optString("id")) ?: return
                            delays.add((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
                            delivered++
                            if (delivered >= sent && !completed.isCompleted) completed.complete(Unit)
                        }
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!completed.isCompleted) completed.complete(Unit)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!completed.isCompleted) completed.complete(Unit)
                }
            })
            withTimeoutOrNull(8_000L) { completed.await() }
            RealProbeAnalysis.message(sent, delivered, delays.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RealProbeResult("message", "Two-device message delivery",
                RealProbeState.UNAVAILABLE, "Could not connect to the configured secure message relay.")
        } finally {
            socket?.cancel()
        }
    }
}
