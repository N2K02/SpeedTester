package com.speedtester.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import android.net.wifi.WifiInfo
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** A manual, bounded diagnostic; there are no services, timers, or periodic network requests. */
object NetworkQualityRunner {
    private data class Target(val service: String, val host: String, val url: String)
    private val targets = listOf(
        Target("Internet", "www.cloudflare.com", "https://www.cloudflare.com/cdn-cgi/trace"),
        Target("Browsing", "www.google.com", "https://www.google.com/generate_204"),
        Target("Streaming", "www.youtube.com", "https://www.youtube.com/generate_204"),
        Target("Gaming", "www.roblox.com", "https://www.roblox.com/"),
        Target("WhatsApp", "web.whatsapp.com", "https://web.whatsapp.com/")
    )

    class NetworkChanged : Exception("Active network changed. Run the test again.")

    @Suppress("DEPRECATION")
    fun access(context: Context): QualityAccess {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            wifi -> "Wi-Fi"
            cell -> "Mobile data"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            network != null -> "Other network"
            else -> "Disconnected"
        }
        val details = if (wifi) (caps?.transportInfo as? WifiInfo)
            ?: (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)?.connectionInfo
            else null
        val signal = details?.rssi?.takeIf { it in -110..-1 }
        val frequency = details?.frequency?.takeIf { it > 0 }
        val gateway = cm.getLinkProperties(network)?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        return QualityAccess(type, signal, frequency, gateway,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }

    suspend fun run(
        context: Context,
        profile: QualityProfile,
        onProgress: suspend (stage: String, completed: Int, total: Int) -> Unit = { _, _, _ -> },
        onInternetCheck: suspend (responded: Boolean) -> Unit = {}
    ): QualityReport {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val snapshot = access(app)
        val started = System.currentTimeMillis()
        val wallStart = SystemClock.elapsedRealtime()
        val cpuStart = Process.getElapsedCpuTime()
        val rxStart = TrafficStats.getUidRxBytes(Process.myUid())
        val txStart = TrafficStats.getUidTxBytes(Process.myUid())
        if (network == null) {
            val empty = QualitySample(null, null, 0, 0, null, "No active network")
            return QualityReport(started, profile, snapshot, empty, emptyList(), null,
                listOf(QualityFinding(false, "Connect to Wi-Fi or mobile data to run a test.")))
        }
        val changed = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(active: Network) {
                if (active != network) changed.set(true)
            }
            override fun onLost(lost: Network) {
                if (lost == network) changed.set(true)
            }
        }
        // A callback catches brief disconnect/reconnect sequences that comparing the current network alone misses.
        // If registration is restricted, the active-network comparison remains the fallback.
        val callbackRegistered = runCatching {
            cm.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)
        try {
        val total = profile.tcpSamples + targets.size * profile.httpsSamples
        var completed = 0

        fun checkNetwork() {
            if (changed.get() || cm.activeNetwork != network) throw NetworkChanged()
        }

        val tcpSamples = ArrayList<Long?>(profile.tcpSamples)
        repeat(profile.tcpSamples) {
            coroutineContext.ensureActive()
            checkNetwork()
            val latency = connect(network)
            coroutineContext.ensureActive()
            checkNetwork()
            tcpSamples += latency
            completed++
            onProgress("TCP connection", completed, total)
        }

        // A real DNS timing is captured from the same pinned-network HTTPS calls.
        // Avoid extra lookup requests and do not claim cached or absent DNS timings.
        val dnsSamples = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val tlsSamples = java.util.Collections.synchronizedList(mutableListOf<Long>())
        // Connections use the selected Android Network rather than switching to mobile data on Wi-Fi failure.
        val client = OkHttpClient.Builder()
            .eventListenerFactory(object : EventListener.Factory {
                override fun create(call: Call): EventListener = object : EventListener() {
                    private var startedNs = 0L
                    private var tlsStartedNs = 0L
                    override fun secureConnectStart(call: Call) {
                        tlsStartedNs = SystemClock.elapsedRealtimeNanos()
                    }
                    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
                        if (tlsStartedNs > 0L) {
                            tlsSamples.add(((SystemClock.elapsedRealtimeNanos() - tlsStartedNs) / 1_000_000)
                                .coerceAtLeast(0L))
                            tlsStartedNs = 0L
                        }
                    }
                    override fun dnsStart(call: Call, domainName: String) {
                        startedNs = SystemClock.elapsedRealtimeNanos()
                    }
                    override fun dnsEnd(
                        call: Call, domainName: String,
                        inetAddressList: List<java.net.InetAddress>
                    ) {
                        if (startedNs > 0L) {
                            val elapsed = ((SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000)
                                .coerceAtLeast(0L)
                            dnsSamples.add(elapsed)
                            startedNs = 0L
                        }
                    }
                }
            })
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    network.getAllByName(hostname).toList()
            })
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1800, TimeUnit.MILLISECONDS)
            .callTimeout(2800, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()

        val endpointResults = mutableListOf<QualityEndpoint>()
        try {
            for (target in targets) {
                val durations = mutableListOf<Long>()
                var lastStatus: Int? = null
                var successes = 0
                var responses = 0
                repeat(profile.httpsSamples) {
                    coroutineContext.ensureActive()
                    checkNetwork()
                    val response = httpsProbe(client, target.url)
                    coroutineContext.ensureActive()
                    checkNetwork()
                    if (response.first != null) {
                        lastStatus = response.first
                        responses++
                        if (response.first in 200..399) successes++
                        response.second?.let(durations::add)
                    }
                    completed++
                    onProgress(target.service, completed, total)
                }
                endpointResults += QualityEndpoint(target.service, target.host,
                    durations.sorted().let { if (it.isEmpty()) null else it[it.size / 2] },
                    lastStatus, profile.httpsSamples, successes, responses)
                if (target.service == "Internet") {
                    // Publish only the actual observed result, never a hypothetical success.
                    onInternetCheck(responses > 0)
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        checkNetwork()
        val tcp = NetworkQualityLogic.evaluate(tcpSamples)
        val rxEnd = TrafficStats.getUidRxBytes(Process.myUid())
        val txEnd = TrafficStats.getUidTxBytes(Process.myUid())
        val traffic = if (minOf(rxStart, txStart, rxEnd, txEnd) < 0 ||
            rxEnd < rxStart || txEnd < txStart) null
            else (rxEnd - rxStart) + (txEnd - txStart)
        return QualityReport(started, profile, snapshot, tcp, endpointResults,
            QualityReportLogic.overall(tcp, endpointResults),
            QualityReportLogic.findings(snapshot, tcp, endpointResults),
            durationMs = SystemClock.elapsedRealtime() - wallStart,
            processCpuMs = Process.getElapsedCpuTime() - cpuStart,
            appTrafficBytes = traffic,
            dnsLookupMs = synchronized(dnsSamples) {
                val sorted = dnsSamples.sorted()
                sorted.getOrNull(sorted.size / 2)
            },
            tlsHandshakeMs = synchronized(tlsSamples) {
                val sorted = tlsSamples.sorted()
                sorted.getOrNull(sorted.size / 2)
            })
        } finally {
            if (callbackRegistered) runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /** Cancellation closes the live socket immediately rather than waiting for the connect timeout. */
    private suspend fun connect(network: Network): Long? = suspendCancellableCoroutine { continuation ->
        val socket = try {
            network.socketFactory.createSocket()
        } catch (_: Exception) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val worker = CoroutineScope(Dispatchers.IO).launch {
            val value = try {
                socket.use {
                    val start = SystemClock.elapsedRealtimeNanos()
                    it.connect(InetSocketAddress("1.1.1.1", 443), 1200)
                    ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).coerceAtLeast(1)
                }
            } catch (_: Exception) { null }
            continuation.resume(value)
        }
        continuation.invokeOnCancellation {
            runCatching { socket.close() }
            worker.cancel()
        }
    }

    /** GET reads response headers only, then closes the body. No streaming or download stress test. */
    private suspend fun httpsProbe(client: OkHttpClient, url: String): Pair<Int?, Long?> =
        suspendCancellableCoroutine { continuation ->
            val start = SystemClock.elapsedRealtimeNanos()
            val call = client.newCall(
                Request.Builder().url(url).get().header("Cache-Control", "no-cache").build()
            )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null to null)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val millis = ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).coerceAtLeast(1)
                        continuation.resume(it.code to millis)
                    }
                }
            })
        }
}
