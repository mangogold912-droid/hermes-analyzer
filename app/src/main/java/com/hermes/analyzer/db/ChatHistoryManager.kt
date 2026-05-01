package com.hermes.analyzer.db

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ChatHistoryManager
 * 파일 업로드와 연결된 채팅 세션을 관리합니다.
 * 사용자가 파일을 업로드하면 세션이 생성되고,
 * 이후 대화가 해당 세션에 지속적으로 누적됩니다.
 */
class ChatHistoryManager private constructor(context: Context) {
    private val TAG = "ChatHistory"
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_chat_history", Context.MODE_PRIVATE)
    private val sessions = mutableMapOf<String, ChatSession>()

    data class ChatMessage(
        val id: String,
        val role: String, // user, ai, system, tool
        val content: String,
        val timestamp: Long,
        val metadata: Map<String, String> = emptyMap()
    )

    data class ChatSession(
        val id: String,
        val title: String,
        val createdAt: Long,
        var updatedAt: Long,
        var attachedFilePath: String? = null,
        var attachedFileType: String? = null,
        val messages: MutableList<ChatMessage> = mutableListOf()
    )

    companion object {
        @Volatile
        private var instance: ChatHistoryManager? = null
        fun getInstance(context: Context): ChatHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: ChatHistoryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        loadAllSessions()
    }

    /**
     * 새 세션 생성 (파일 업로드 시 호출)
     */
    fun createSession(title: String, filePath: String? = null, fileType: String? = null): ChatSession {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            attachedFilePath = filePath,
            attachedFileType = fileType
        )
        sessions[id] = session
        saveSession(session)
        Log.i(TAG, "Created session $id with file=$filePath")
        return session
    }

    /**
     * 메시지 추가
     */
    fun addMessage(sessionId: String, role: String, content: String, metadata: Map<String, String> = emptyMap()): ChatMessage? {
        val session = sessions[sessionId] ?: return null
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            metadata = metadata
        )
        session.messages.add(msg)
        session.updatedAt = msg.timestamp
        saveSession(session)
        return msg
    }

    /**
     * 특정 세션의 대화를 Pair<role, content> 형태로 반환 (LLM 컨텍스트용)
     */
    fun getConversationPairs(sessionId: String, limit: Int = 20): List<Pair<String, String>> {
        val session = sessions[sessionId] ?: return emptyList()
        return session.messages.takeLast(limit).map { it.role to it.content }
    }

    /**
     * 세션 조회
     */
    fun getSession(id: String): ChatSession? = sessions[id]
    fun listSessions(): List<ChatSession> = sessions.values.sortedByDescending { it.updatedAt }

    /**
     * 키워드로 모든 세션 검색
     */
    fun searchInSessions(keyword: String): List<Pair<ChatSession, List<ChatMessage>>> {
        val results = mutableListOf<Pair<ChatSession, List<ChatMessage>>>()
        val lower = keyword.lowercase()
        for (session in sessions.values) {
            val matched = session.messages.filter {
                it.content.lowercase().contains(lower) ||
                it.metadata.values.any { v -> v.lowercase().contains(lower) }
            }
            if (matched.isNotEmpty() || session.title.lowercase().contains(lower)) {
                results.add(session to matched)
            }
        }
        return results.sortedByDescending { it.first.updatedAt }
    }

    /**
     * 세션을 Markdown으로보내기
     */
    fun exportSession(sessionId: String): String? {
        val session = sessions[sessionId] ?: return null
        val sb = StringBuilder()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        sb.append("# ${session.title}\n\n")
        sb.append("- **Session ID:** ${session.id}\n")
        sb.append("- **Created:** ${dateFmt.format(Date(session.createdAt))}\n")
        sb.append("- **Updated:** ${dateFmt.format(Date(session.updatedAt))}\n")
        if (session.attachedFilePath != null) {
            sb.append("- **Attached File:** `${session.attachedFilePath}` (${session.attachedFileType ?: "unknown"})\n")
        }
        sb.append("\n---\n\n")

        for (msg in session.messages) {
            val time = dateFmt.format(Date(msg.timestamp))
            when (msg.role) {
                "user" -> sb.append("**User** ($time):\n${msg.content}\n\n")
                "ai" -> sb.append("**Hermes AI** ($time):\n${msg.content}\n\n")
                "system" -> sb.append("*System ($time): ${msg.content}*\n\n")
                "tool" -> sb.append("`Tool ($time): ${msg.content}`\n\n")
                else -> sb.append("${msg.role} ($time): ${msg.content}\n\n")
            }
        }

        return sb.toString()
    }

    /**
     * 세션 삭제
     */
    fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        prefs.edit().remove("session_$sessionId").apply()
    }

    /**
     * 모든 세션을 SharedPreferences에 저장
     */
    private fun saveSession(session: ChatSession) {
        val json = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
            put("attachedFilePath", session.attachedFilePath ?: JSONObject.NULL)
            put("attachedFileType", session.attachedFileType ?: JSONObject.NULL)
            val msgArray = JSONArray()
            for (msg in session.messages) {
                val msgObj = JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                    put("metadata", JSONObject(msg.metadata))
                }
                msgArray.put(msgObj)
            }
            put("messages", msgArray)
        }
        prefs.edit().putString("session_${session.id}", json.toString()).apply()
        prefs.edit().putString("session_ids", sessions.keys.joinToString(",")).apply()
    }

    /**
     * 모든 세션 로드
     */
    private fun loadAllSessions() {
        val idsStr = prefs.getString("session_ids", "") ?: ""
        val ids = idsStr.split(",").filter { it.isNotBlank() }
        for (id in ids) {
            val jsonStr = prefs.getString("session_$id", null) ?: continue
            try {
                val json = JSONObject(jsonStr)
                val session = ChatSession(
                    id = json.getString("id"),
                    title = json.getString("title"),
                    createdAt = json.getLong("createdAt"),
                    updatedAt = json.getLong("updatedAt"),
                    attachedFilePath = json.optString("attachedFilePath", null),
                    attachedFileType = json.optString("attachedFileType", null)
                )
                val msgArray = json.getJSONArray("messages")
                for (i in 0 until msgArray.length()) {
                    val msgObj = msgArray.getJSONObject(i)
                    val metaObj = msgObj.optJSONObject("metadata")
                    val meta = mutableMapOf<String, String>()
                    metaObj?.let {
                        val keys = it.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            meta[k] = it.getString(k)
                        }
                    }
                    session.messages.add(ChatMessage(
                        id = msgObj.getString("id"),
                        role = msgObj.getString("role"),
                        content = msgObj.getString("content"),
                        timestamp = msgObj.getLong("timestamp"),
                        metadata = meta
                    ))
                }
                sessions[id] = session
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load session $id: ${e.message}")
            }
        }
        Log.i(TAG, "Loaded ${sessions.size} sessions")
    }

    /**
     * 마지막 활성 세션 ID (최근 업데이트)
     */
    fun getLastActiveSessionId(): String? {
        return sessions.values.maxByOrNull { it.updatedAt }?.id
    }
}
