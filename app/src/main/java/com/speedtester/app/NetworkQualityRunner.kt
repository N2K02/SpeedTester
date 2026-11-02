package com.speedtester.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

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
            wifi -> "Wi-Fi"
            cell -> "Mobile data"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
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
        onProgress: suspend (stage: String, completed: Int, total: Int) -> Unit = { _, _, _ -> }
    ): QualityReport {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val snapshot = access(app)
        val started = System.currentTimeMillis()
        if (network == null) {
            val empty = QualitySample(null, null, 0, 0, null, "No active network")
            return QualityReport(started, profile, snapshot, empty, emptyList(), null,
                listOf(QualityFinding(false, "Connect to Wi-Fi or mobile data to run a test.")))
        }
        val total = profile.tcpSamples + targets.size * profile.httpsSamples
        var completed = 0

        fun checkNetwork() {
            if (cm.activeNetwork != network) throw NetworkChanged()
        }

        val tcpSamples = ArrayList<Long?>(profile.tcpSamples)
        repeat(profile.tcpSamples) {
            coroutineContext.ensureActive()
            checkNetwork()
            val latency = withContext(Dispatchers.IO) { connect(network) }
            tcpSamples += latency
            completed++
            onProgress("TCP connection", completed, total)
        }

        // Connections use the selected Android Network rather than switching to mobile data on Wi-Fi failure.
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns(Dns { host -> network.getAllByName(host).toList() })
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
                repeat(profile.httpsSamples) {
                    coroutineContext.ensureActive()
                    checkNetwork()
                    val response = withContext(Dispatchers.IO) { httpsHead(client, target.url) }
                    if (response.first != null) {
                        lastStatus = response.first
                        if (response.first in 200..399) {
                            successes++
                            response.second?.let(durations::add)
                        }
                    }
                    completed++
                    onProgress(target.service, completed, total)
                }
                endpointResults += QualityEndpoint(target.service, target.host,
                    durations.sorted().let { if (it.isEmpty()) null else it[it.size / 2] },
                    lastStatus, profile.httpsSamples, successes)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        checkNetwork()
        val tcp = NetworkQualityLogic.evaluate(tcpSamples)
        return QualityReport(started, profile, snapshot, tcp, endpointResults,
            QualityReportLogic.overall(tcp, endpointResults),
            QualityReportLogic.findings(snapshot, tcp, endpointResults))
    }

    private fun connect(network: Network): Long? {
        return try {
            network.socketFactory.createSocket().use { socket ->
                val start = SystemClock.elapsedRealtimeNanos()
                socket.connect(InetSocketAddress("1.1.1.1", 443), 1200)
                ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).coerceAtLeast(1)
            }
        } catch (_: java.io.IOException) { null }
    }

    /** HEAD requests read only headers; these are not video, game, or message performance tests. */
    private fun httpsHead(client: OkHttpClient, url: String): Pair<Int?, Long?> {
        return try {
            val start = SystemClock.elapsedRealtimeNanos()
            client.newCall(Request.Builder().url(url).head().header("Cache-Control", "no-cache").build())
                .execute().use { response ->
                    val millis = ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000).coerceAtLeast(1)
                    response.code to millis
                }
        } catch (_: java.io.IOException) { null to null }
    }
}
