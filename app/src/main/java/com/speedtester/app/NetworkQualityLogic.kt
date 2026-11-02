package com.speedtester.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Bounded, low-data diagnostic. TCP connect timings are NOT ICMP ping or packet-loss measurements. */
data class QualitySample(val latencyMs: Long?, val jitterMs: Double?, val successes: Int, val attempts: Int, val grade: Int?, val note: String)
object NetworkQualityLogic {
    fun evaluate(samples: List<Long?>): QualitySample {
        val valid = samples.filterNotNull()
        if (valid.isEmpty()) return QualitySample(null, null, 0, samples.size, null, "Connection unavailable")
        val sorted = valid.sorted()
        val median = sorted[sorted.size / 2]
        val jitter = valid.zipWithNext().map { (a,b) -> kotlin.math.abs(a-b).toDouble() }.average().takeIf { !it.isNaN() } ?: 0.0
        val failures = samples.size - valid.size
        val score = (100.0 - median * 0.35 - jitter * 0.7 - failures * 12).roundToInt().coerceIn(0,100)
        return QualitySample(median,jitter,valid.size,samples.size,score,
            if (failures > 0) "$failures TCP connections failed; this does not measure packet loss" else "TCP connection responsiveness")
    }
    fun label(score: Int?): String = when {
        score == null -> "Unavailable"
        score >= 90 -> "Excellent"
        score >= 75 -> "Very good"
        score >= 60 -> "Good"
        score >= 40 -> "Fair"
        else -> "Poor"
    }
}
object NetworkQualityProbe {
    suspend fun run(host: String = "1.1.1.1", port: Int = 443): QualitySample = withContext(Dispatchers.IO) {
        val samples = ArrayList<Long?>(6)
        repeat(6) {
            coroutineContext.ensureActive()
            val started = System.nanoTime()
            val value = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 1200)
                    ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                }
            } catch (e: java.io.IOException) { null }
            samples.add(value)
        }
        NetworkQualityLogic.evaluate(samples)
    }
}
