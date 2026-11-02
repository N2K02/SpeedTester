package com.speedtester.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local-only compact history. Does not persist SSIDs, BSSIDs, IP addresses or endpoint URLs. */
data class QualityHistoryItem(val timestamp: Long, val score: Int?, val transport: String, val tcpMs: Long?)
object NetworkQualityHistory {
    private const val KEY = "network_quality_history_v1"
    private const val LIMIT = 15

    fun load(context: Context): List<QualityHistoryItem> {
        val raw = context.getSharedPreferences("speedtester", Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until minOf(array.length(), LIMIT)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val date = item.optLong("time", 0)
                if (date <= 0) return@mapNotNull null
                QualityHistoryItem(date,
                    item.optInt("score").takeIf { item.has("score") && it in 0..100 },
                    item.optString("transport", "Unknown").take(24),
                    item.optLong("tcp").takeIf { item.has("tcp") && it >= 0 })
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, report: QualityReport): List<QualityHistoryItem> {
        val items = (listOf(QualityHistoryItem(report.startedAt, report.score,
            report.access.transport, report.tcp.latencyMs)) + load(context)).take(LIMIT)
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("time", item.timestamp)
                item.score?.let { put("score", it) }
                put("transport", item.transport)
                item.tcpMs?.let { put("tcp", it) }
            })
        }
        context.getSharedPreferences("speedtester", Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
        return items
    }
}
