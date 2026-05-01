package com.hermes.analyzer.memory

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatHistoryManager
 * 채팅 기록 영구 저장, 파일 연속 분석, 세션 관리
 */
class ChatHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_chat_history", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class ChatSession(
        val sessionId: String,
        val title: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<ChatMessage>,
        val attachedFile: String?,
        val analysisGoal: String
    )

    data class ChatMessage(
        val id: String,
        val role: String, // user, ai, system
        val content: String,
        val timestamp: Long,
        val attachedFile: String?,
        val analysisType: String?,
        val tokensUsed: Int = 0
    )

    fun createSession(title: String, filePath: String? = null, goal: String = "General Analysis"): String {
        val id = "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val session = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("createdAt", System.currentTimeMillis())
            put("updatedAt", System.currentTimeMillis())
            put("messages", JSONArray())
            put("attachedFile", filePath)
            put("analysisGoal", goal)
        }
        saveSession(id, session)
        return id
    }

    fun addMessage(sessionId: String, role: String, content: String, filePath: String? = null, analysisType: String? = null) {
        val raw = prefs.getString("session_$sessionId", null) ?: return
        val session = JSONObject(raw)
        val messages = session.getJSONArray("messages")
        val msg = JSONObject().apply {
            put("id", "msg_${System.currentTimeMillis()}_${messages.length()}")
            put("role", role)
            put("content", content)
            put("timestamp", System.currentTimeMillis())
            put("attachedFile", filePath)
            put("analysisType", analysisType)
            put("tokensUsed", content.length / 4)
        }
        messages.put(msg)
        session.put("messages", messages)
        session.put("updatedAt", System.currentTimeMillis())
        saveSession(sessionId, session)
    }

    fun getSession(sessionId: String): ChatSession? {
        val raw = prefs.getString("session_$sessionId", null) ?: return null
        return parseSession(raw)
    }

    fun getAllSessions(): List<ChatSession> {
        return prefs.all.keys.filter { it.startsWith("session_") }.mapNotNull { key ->
            prefs.getString(key, null)?.let { parseSession(it) }
        }.sortedByDescending { it.updatedAt }
    }

    fun getSessionsForFile(filePath: String): List<ChatSession> {
        return getAllSessions().filter { it.attachedFile == filePath }
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        val raw = prefs.getString("session_$sessionId", null) ?: return
        val session = JSONObject(raw)
        session.put("title", title)
        saveSession(sessionId, session)
    }

    fun setAttachedFile(sessionId: String, filePath: String?) {
        val raw = prefs.getString("session_$sessionId", null) ?: return
        val session = JSONObject(raw)
        session.put("attachedFile", filePath)
        saveSession(sessionId, session)
    }

    fun deleteSession(sessionId: String) {
        prefs.edit().remove("session_$sessionId").apply()
    }

    fun exportSession(sessionId: String): String {
        val session = getSession(sessionId) ?: return "{}"
        val sb = StringBuilder()
        sb.append("# ${session.title}\n\n")
        sb.append("**File**: ${session.attachedFile ?: "None"}\n")
        sb.append("**Goal**: ${session.analysisGoal}\n")
        sb.append("**Date**: ${dateFormat.format(Date(session.createdAt))}\n\n")
        sb.append("---\n\n")
        session.messages.forEach { msg ->
            val emoji = when (msg.role) {
                "user" -> "👤"
                "ai" -> "🤖"
                "system" -> "⚙️"
                else -> "💬"
            }
            sb.append("$emoji **${msg.role.uppercase()}** (${dateFormat.format(Date(msg.timestamp))})\n")
            sb.append("${msg.content}\n\n")
        }
        return sb.toString()
    }

    fun searchInSessions(query: String): List<Pair<ChatSession, ChatMessage>> {
        val results = mutableListOf<Pair<ChatSession, ChatMessage>>()
        val lower = query.lowercase()
        getAllSessions().forEach { session ->
            session.messages.forEach { msg ->
                if (msg.content.lowercase().contains(lower) || msg.analysisType?.lowercase()?.contains(lower) == true) {
                    results.add(session to msg)
                }
            }
        }
        return results
    }

    fun getTotalMessageCount(): Int {
        return getAllSessions().sumOf { it.messages.size }
    }

    fun clearOldSessions(keepCount: Int = 50) {
        val all = getAllSessions()
        if (all.size > keepCount) {
            val toDelete = all.drop(keepCount)
            val editor = prefs.edit()
            toDelete.forEach { editor.remove("session_${it.sessionId}") }
            editor.apply()
        }
    }

    private fun saveSession(id: String, session: JSONObject) {
        prefs.edit().putString("session_$id", session.toString()).apply()
    }

    private fun parseSession(raw: String): ChatSession {
        val obj = JSONObject(raw)
        val msgs = obj.getJSONArray("messages")
        val messages = (0 until msgs.length()).map { i ->
            val m = msgs.getJSONObject(i)
            ChatMessage(
                m.getString("id"),
                m.getString("role"),
                m.getString("content"),
                m.getLong("timestamp"),
                m.optString("attachedFile", null),
                m.optString("analysisType", null),
                m.optInt("tokensUsed", 0)
            )
        }
        return ChatSession(
            obj.getString("id"),
            obj.getString("title"),
            obj.getLong("createdAt"),
            obj.getLong("updatedAt"),
            messages,
            obj.optString("attachedFile", null),
            obj.optString("analysisGoal", "General Analysis")
        )
    }
}
