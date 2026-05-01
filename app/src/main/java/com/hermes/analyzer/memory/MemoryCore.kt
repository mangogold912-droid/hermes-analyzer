package com.hermes.analyzer.memory

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Memory Core
 * 장기 메모리 및 프로젝트 컨텍스트 저장 (대화, 프로젝트, 사용자 선호, 도구 사용 기록)
 */
class MemoryCore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_memory", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    data class ConversationMemory(
        val id: String,
        val projectId: String,
        val messages: List<MessageEntry>,
        val createdAt: Long,
        val updatedAt: Long
    )

    data class MessageEntry(
        val role: String,
        val content: String,
        val timestamp: Long,
        val fileAttachment: String? = null
    )

    data class ProjectMemory(
        val id: String,
        val name: String,
        val description: String,
        val files: List<String>,
        val goals: List<String>,
        val lastAccessed: Long
    )

    // Conversation management
    fun createConversation(projectId: String = "default"): String {
        val id = "conv_${System.currentTimeMillis()}"
        val conv = JSONObject().apply {
            put("id", id)
            put("projectId", projectId)
            put("messages", JSONArray())
            put("createdAt", System.currentTimeMillis())
            put("updatedAt", System.currentTimeMillis())
        }
        saveConversation(id, conv)
        return id
    }

    fun addMessage(convId: String, role: String, content: String, filePath: String? = null) {
        val conv = loadConversation(convId) ?: return
        val messages = conv.getJSONArray("messages")
        messages.put(JSONObject().apply {
            put("role", role)
            put("content", content)
            put("timestamp", System.currentTimeMillis())
            filePath?.let { put("fileAttachment", it) }
        })
        conv.put("messages", messages)
        conv.put("updatedAt", System.currentTimeMillis())
        saveConversation(convId, conv)
    }

    fun getConversation(convId: String): ConversationMemory? {
        val conv = loadConversation(convId) ?: return null
        val messages = (0 until conv.getJSONArray("messages").length()).map { i ->
            val msg = conv.getJSONArray("messages").getJSONObject(i)
            MessageEntry(
                msg.getString("role"),
                msg.getString("content"),
                msg.getLong("timestamp"),
                msg.optString("fileAttachment", null)
            )
        }
        return ConversationMemory(
            conv.getString("id"),
            conv.getString("projectId"),
            messages,
            conv.getLong("createdAt"),
            conv.getLong("updatedAt")
        )
    }

    fun listConversations(projectId: String? = null): List<String> {
        val all = prefs.getStringSet("conversation_ids", emptySet()) ?: emptySet()
        return if (projectId == null) {
            all.toList()
        } else {
            all.filter { id ->
                loadConversation(id)?.optString("projectId") == projectId
            }
        }
    }

    // Project management
    fun createProject(name: String, description: String = ""): String {
        val id = "proj_${System.currentTimeMillis()}"
        val proj = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("files", JSONArray())
            put("goals", JSONArray())
            put("lastAccessed", System.currentTimeMillis())
        }
        prefs.edit().putString("project_$id", proj.toString()).apply()
        return id
    }

    fun addFileToProject(projectId: String, filePath: String) {
        val proj = JSONObject(prefs.getString("project_$projectId", "{}") ?: "{}")
        val files = proj.optJSONArray("files") ?: JSONArray()
        files.put(filePath)
        proj.put("files", files)
        proj.put("lastAccessed", System.currentTimeMillis())
        prefs.edit().putString("project_$projectId", proj.toString()).apply()
    }

    fun addGoalToProject(projectId: String, goal: String) {
        val proj = JSONObject(prefs.getString("project_$projectId", "{}") ?: "{}")
        val goals = proj.optJSONArray("goals") ?: JSONArray()
        goals.put(goal)
        proj.put("goals", goals)
        prefs.edit().putString("project_$projectId", proj.toString()).apply()
    }

    fun getProjects(): List<ProjectMemory> {
        return prefs.all.keys.filter { it.startsWith("project_") }.mapNotNull { key ->
            val id = key.removePrefix("project_")
            val proj = JSONObject(prefs.getString(key, "{}") ?: "{}")
            if (proj.has("id")) {
                val files = proj.optJSONArray("files")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                val goals = proj.optJSONArray("goals")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                ProjectMemory(id, proj.getString("name"), proj.optString("description", ""), files, goals, proj.optLong("lastAccessed", 0))
            } else null
        }
    }

    // User preferences
    fun savePreference(key: String, value: String) {
        prefs.edit().putString("pref_$key", value).apply()
    }

    fun getPreference(key: String, default: String = ""): String {
        return prefs.getString("pref_$key", default) ?: default
    }

    // Tool usage history
    fun recordToolUsage(toolName: String, success: Boolean, durationMs: Long) {
        val history = JSONArray(prefs.getString("tool_history", "[]") ?: "[]")
        history.put(JSONObject().apply {
            put("tool", toolName)
            put("success", success)
            put("duration", durationMs)
            put("time", System.currentTimeMillis())
        })
        if (history.length() > 1000) {
            val trimmed = JSONArray()
            (history.length() - 500 until history.length()).forEach { trimmed.put(history.get(it)) }
            prefs.edit().putString("tool_history", trimmed.toString()).apply()
        } else {
            prefs.edit().putString("tool_history", history.toString()).apply()
        }
    }

    private fun saveConversation(id: String, conv: JSONObject) {
        val ids = (prefs.getStringSet("conversation_ids", emptySet()) ?: emptySet()).toMutableSet()
        ids.add(id)
        prefs.edit()
            .putString("conversation_$id", conv.toString())
            .putStringSet("conversation_ids", ids)
            .apply()
    }

    private fun loadConversation(id: String): JSONObject? {
        val raw = prefs.getString("conversation_$id", null) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }
}
