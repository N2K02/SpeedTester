package com.speedtester.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A locally stored test result. Never store SSIDs, BSSIDs, gateway IPs or endpoint URLs. */
data class QualityHistoryItem(
    val timestamp: Long,
    val score: Int?,
    val transport: String,
    val tcpMs: Long?,
    val dnsMs: Long? = null,
    val tlsMs: Long? = null,
    val jitterMs: Double? = null,
    val httpsResponses: Int = 0,
    val httpsAttempts: Int = 0,
    val profile: String = "STANDARD",
    val durationMs: Long? = null,
    val cpuMs: Long? = null,
    val trafficBytes: Long? = null,
    val scenarios: List<QualityScenarioResult> = emptyList()
)

/** Backward-compatible, bounded JSON codec so old compact quality results remain visible. */
internal object QualityHistoryCodec {
    const val LIMIT = 30
    fun decode(raw: String): List<QualityHistoryItem> = runCatching {
        val items = JSONArray(raw)
        (0 until minOf(items.length(), LIMIT)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val stamp = item.optLong("time", 0)
            if (stamp <= 0L) return@mapNotNull null
            val scenarios = item.optJSONArray("scenarios") ?: JSONArray()
            val findings = (0 until minOf(scenarios.length(), 16)).mapNotNull { idx ->
                val obj = scenarios.optJSONObject(idx) ?: return@mapNotNull null
                val id = obj.optString("id").take(40)
                if (id.isBlank()) return@mapNotNull null
                val state = runCatching {
                    QualityScenarioState.valueOf(obj.optString("state"))
                }.getOrDefault(QualityScenarioState.UNKNOWN)
                QualityScenarioResult(id, obj.optString("title").take(80),
                    state, obj.optString("detail").take(300))
            }
            fun long(name: String) = item.optLong(name).takeIf { item.has(name) && it >= 0 }
            fun int(name: String) = item.optInt(name, 0).coerceIn(0, 200)
            QualityHistoryItem(
                timestamp = stamp,
                score = item.optInt("score").takeIf { item.has("score") && it in 0..100 },
                transport = item.optString("transport", "Unknown").take(24),
                tcpMs = long("tcp"),
                dnsMs = long("dns"),
                tlsMs = long("tls"),
                jitterMs = item.optDouble("jitter").takeIf {
                    item.has("jitter") && it.isFinite() && it >= 0.0
                },
                httpsResponses = int("httpsResponses"),
                httpsAttempts = int("httpsAttempts"),
                profile = item.optString("profile", "STANDARD").take(24),
                durationMs = long("duration"),
                cpuMs = long("cpu"),
                trafficBytes = long("traffic"),
                scenarios = findings
            )
        }.sortedByDescending { it.timestamp }
    }.getOrDefault(emptyList())

    fun encode(items: List<QualityHistoryItem>): String = JSONArray().apply {
        items.sortedByDescending { it.timestamp }.take(LIMIT).forEach { item ->
            put(JSONObject().apply {
                put("time", item.timestamp)
                item.score?.let { put("score", it) }
                put("transport", item.transport.take(24))
                item.tcpMs?.let { put("tcp", it) }
                item.dnsMs?.let { put("dns", it) }
                item.tlsMs?.let { put("tls", it) }
                item.jitterMs?.takeIf { it.isFinite() && it >= 0 }?.let { put("jitter", it) }
                put("httpsResponses", item.httpsResponses)
                put("httpsAttempts", item.httpsAttempts)
                put("profile", item.profile.take(24))
                item.durationMs?.let { put("duration", it) }
                item.cpuMs?.let { put("cpu", it) }
                item.trafficBytes?.let { put("traffic", it) }
                put("scenarios", JSONArray().apply {
                    item.scenarios.take(16).forEach { scenario ->
                        put(JSONObject().apply {
                            put("id", scenario.id.take(40))
                            put("title", scenario.title.take(80))
                            put("state", scenario.state.name)
                            put("detail", scenario.detail.take(300))
                        })
                    }
                })
            })
        }
    }.toString()
}

object NetworkQualityHistory {
    private const val KEY = "network_quality_history_v1"

    fun load(context: Context): List<QualityHistoryItem> {
        val prefs = context.getSharedPreferences("speedtester", Context.MODE_PRIVATE)
        return QualityHistoryCodec.decode(prefs.getString(KEY, "[]") ?: "[]")
    }

    fun clear(context: Context) {
        context.getSharedPreferences("speedtester", Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun delete(context: Context, timestamp: Long): List<QualityHistoryItem> {
        val remaining = load(context).filterNot { it.timestamp == timestamp }
        context.getSharedPreferences("speedtester", Context.MODE_PRIVATE)
            .edit().putString(KEY, QualityHistoryCodec.encode(remaining)).apply()
        return remaining
    }

    fun save(context: Context, report: QualityReport): List<QualityHistoryItem> {
        val item = QualityHistoryItem(
            timestamp = report.startedAt,
            score = report.score,
            transport = report.access.transport,
            tcpMs = report.tcp.latencyMs,
            dnsMs = report.dnsLookupMs,
            tlsMs = report.tlsHandshakeMs,
            jitterMs = report.tcp.jitterMs,
            httpsResponses = report.endpoints.sumOf { it.responses },
            httpsAttempts = report.endpoints.sumOf { it.attempted },
            profile = report.profile.name,
            durationMs = report.durationMs,
            cpuMs = report.processCpuMs,
            trafficBytes = report.appTrafficBytes,
            scenarios = QualityScenarioLogic.evaluate(report)
        )
        val items = (listOf(item) + load(context)).take(QualityHistoryCodec.LIMIT)
        context.getSharedPreferences("speedtester", Context.MODE_PRIVATE)
            .edit().putString(KEY, QualityHistoryCodec.encode(items)).apply()
        return items
    }
}
