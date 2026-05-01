package com.hermes.analyzer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Security audit log manager for tracking all AI actions, tool executions,
 * file changes, and network requests with user approval records.
 */
class AuditLogManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_audit_logs", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    data class AuditEntry(
        val time: String,
        val actor: String,
        val action: String,
        val command: String,
        val result: String,
        val filesChanged: List<String>,
        val networkUsed: Boolean
    )

    fun log(actor: String, action: String, command: String = "", result: String = "success", filesChanged: List<String> = emptyList(), networkUsed: Boolean = false) {
        val entry = JSONObject().apply {
            put("time", dateFormat.format(Date()))
            put("actor", actor)
            put("action", action)
            put("command", command)
            put("result", result)
            put("filesChanged", JSONArray(filesChanged))
            put("networkUsed", networkUsed)
        }
        val logs = getRawLogs().apply { put(entry) }
        // Keep only last 500 entries
        while (logs.length() > 500) {
            logs.remove(0)
        }
        prefs.edit().putString("logs", logs.toString()).apply()
    }

    fun getRecentLogs(limit: Int = 50): List<AuditEntry> {
        val logs = getRawLogs()
        val result = mutableListOf<AuditEntry>()
        val start = maxOf(0, logs.length() - limit)
        for (i in start until logs.length()) {
            val obj = logs.getJSONObject(i)
            result.add(AuditEntry(
                time = obj.optString("time", ""),
                actor = obj.optString("actor", ""),
                action = obj.optString("action", ""),
                command = obj.optString("command", ""),
                result = obj.optString("result", ""),
                filesChanged = obj.optJSONArray("filesChanged")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                networkUsed = obj.optBoolean("networkUsed", false)
            ))
        }
        return result.reversed()
    }

    fun getRawLogs(): JSONArray {
        return try {
            JSONArray(prefs.getString("logs", "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun clear() {
        prefs.edit().remove("logs").apply()
    }

    fun export(): String {
        return getRawLogs().toString(2)
    }
}
