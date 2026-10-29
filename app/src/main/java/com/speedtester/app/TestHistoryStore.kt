package com.speedtester.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe test history storage. Android AtomicFile preserves the previous
 * complete version when power is lost during a write. Legacy SharedPreferences
 * history is read as a one-time migration fallback.
 */
internal object TestHistoryStore {
    private fun file(context: Context) =
        AtomicFile(File(context.filesDir, "speedtester-history.json"))

    fun read(context: Context, legacyJson: String?): String {
        val recovered = runCatching {
            String(file(context).readFully(), Charsets.UTF_8)
        }.getOrNull()
        val candidate = recovered?.takeIf { runCatching { JSONArray(it) }.isSuccess }
        return candidate ?: legacyJson?.takeIf { runCatching { JSONArray(it) }.isSuccess } ?: "[]"
    }

    fun write(context: Context, history: JSONArray) {
        val target = file(context)
        var stream: FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(history.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) target.failWrite(stream)
            throw error
        }
    }
}
