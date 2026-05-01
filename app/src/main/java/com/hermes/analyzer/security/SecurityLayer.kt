package com.hermes.analyzer.security

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Date

/**
 * Security Layer
 * 권한 기반 보안 구조, 승인 기반 실행, 사용자 승인 관리
 */
class SecurityLayer(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_security", Context.MODE_PRIVATE)

    data class PermissionRequest(
        val action: String,
        val resource: String,
        val reason: String,
        val riskLevel: String, // low, medium, high, critical
        val timestamp: Long
    )

    companion object {
        const val PERM_FILE_READ = "file.read"
        const val PERM_FILE_WRITE = "file.write"
        const val PERM_NETWORK = "network.request"
        const val PERM_SANDBOX_EXEC = "sandbox.execute"
        const val PERM_GITHUB_READ = "github.read"
        const val PERM_GITHUB_WRITE = "github.write"
        const val PERM_BROWSER_DOWNLOAD = "browser.download"
        const val PERM_IDA_SCRIPT = "ida.script"
        const val PERM_PLUGIN_INSTALL = "plugin.install"
        const val PERM_MEMORY_SENSITIVE = "memory.sensitive"
    }

    fun checkPermission(permission: String): Boolean {
        return prefs.getBoolean("perm_$permission", false)
    }

    fun grantPermission(permission: String) {
        prefs.edit().putBoolean("perm_$permission", true).apply()
    }

    fun revokePermission(permission: String) {
        prefs.edit().putBoolean("perm_$permission", false).apply()
    }

    fun requestApproval(action: String, resource: String, reason: String, riskLevel: String): Boolean {
        // For high/critical risk, always require explicit approval
        // For medium risk, require approval if not previously granted
        // For low risk, auto-approve
        return when (riskLevel) {
            "low" -> true
            "medium" -> checkPermission(action) || false
            "high", "critical" -> false // Must be explicitly approved by user
            else -> false
        }
    }

    fun logSecurityEvent(actor: String, action: String, resource: String, approved: Boolean, userId: String = "default") {
        val event = JSONObject().apply {
            put("time", Date().toString())
            put("actor", actor)
            put("action", action)
            put("resource", resource)
            put("approved", approved)
            put("userId", userId)
        }
        val events = prefs.getStringSet("security_events", mutableSetOf()) ?: mutableSetOf()
        events.add(event.toString())
        prefs.edit().putStringSet("security_events", events).apply()
    }

    fun getSecurityEvents(limit: Int = 50): List<String> {
        val events = prefs.getStringSet("security_events", emptySet()) ?: emptySet()
        return events.toList().sortedDescending().take(limit)
    }

    fun isDangerousAction(action: String): Boolean {
        return action in listOf(
            PERM_SANDBOX_EXEC,
            PERM_GITHUB_WRITE,
            PERM_PLUGIN_INSTALL,
            PERM_IDA_SCRIPT,
            PERM_MEMORY_SENSITIVE
        )
    }

    fun getRiskLevel(action: String): String {
        return when (action) {
            PERM_FILE_READ -> "low"
            PERM_NETWORK -> "medium"
            PERM_SANDBOX_EXEC -> "high"
            PERM_GITHUB_WRITE -> "high"
            PERM_PLUGIN_INSTALL -> "high"
            PERM_IDA_SCRIPT -> "medium"
            PERM_MEMORY_SENSITIVE -> "critical"
            else -> "medium"
        }
    }
}
