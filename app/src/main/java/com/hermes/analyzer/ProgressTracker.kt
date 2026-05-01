package com.hermes.analyzer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * ProgressTracker
 * 실시간 작업 진행률 추적 및 표시
 */
class ProgressTracker(context: Context) {
    private val prefs = context.getSharedPreferences("hermes_progress", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    data class TaskProgress(
        val taskId: String,
        val name: String,
        val percent: Int,
        val status: String,
        val logs: List<String>
    )

    fun startTask(taskId: String, name: String) {
        val task = JSONObject().apply {
            put("id", taskId)
            put("name", name)
            put("percent", 0)
            put("status", "running")
            put("logs", JSONArray())
            put("startTime", System.currentTimeMillis())
        }
        prefs.edit().putString("task_$taskId", task.toString()).apply()
    }

    fun updateProgress(taskId: String, percent: Int, status: String) {
        val raw = prefs.getString("task_$taskId", null) ?: return
        val task = JSONObject(raw)
        task.put("percent", percent)
        task.put("status", status)
        prefs.edit().putString("task_$taskId", task.toString()).apply()
    }

    fun log(taskId: String, message: String) {
        val raw = prefs.getString("task_$taskId", null) ?: return
        val task = JSONObject(raw)
        val logs = task.getJSONArray("logs")
        logs.put("${System.currentTimeMillis()}: $message")
        if (logs.length() > 100) {
            val trimmed = JSONArray()
            (logs.length() - 50 until logs.length()).forEach { trimmed.put(logs.get(it)) }
            task.put("logs", trimmed)
        }
        prefs.edit().putString("task_$taskId", task.toString()).apply()
    }

    fun completeTask(taskId: String, result: String) {
        val raw = prefs.getString("task_$taskId", null) ?: return
        val task = JSONObject(raw)
        task.put("percent", 100)
        task.put("status", "completed")
        task.put("result", result)
        task.put("endTime", System.currentTimeMillis())
        prefs.edit().putString("task_$taskId", task.toString()).apply()
    }

    fun failTask(taskId: String, error: String) {
        val raw = prefs.getString("task_$taskId", null) ?: return
        val task = JSONObject(raw)
        task.put("status", "failed")
        task.put("error", error)
        prefs.edit().putString("task_$taskId", task.toString()).apply()
    }

    fun getTask(taskId: String): TaskProgress? {
        val raw = prefs.getString("task_$taskId", null) ?: return null
        val task = JSONObject(raw)
        val logs = task.optJSONArray("logs")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        return TaskProgress(
            task.getString("id"),
            task.getString("name"),
            task.optInt("percent", 0),
            task.optString("status", "unknown"),
            logs
        )
    }

    fun listActiveTasks(): List<String> {
        return prefs.all.keys.filter { it.startsWith("task_") }.map { it.removePrefix("task_") }
    }

    fun clearCompleted() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("task_") }.forEach { key ->
            val raw = prefs.getString(key, null)
            raw?.let {
                val task = JSONObject(it)
                if (task.optString("status") in listOf("completed", "failed")) {
                    editor.remove(key)
                }
            }
        }
        editor.apply()
    }
}
