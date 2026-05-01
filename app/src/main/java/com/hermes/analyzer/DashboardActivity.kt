package com.hermes.analyzer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DashboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)
        title = "Hermes Dashboard"

        val prefs = getSharedPreferences("hermes_dashboard", MODE_PRIVATE)

        // Header
        root.addView(TextView(this).apply {
            text = "Hermes Analyzer Dashboard"
            textSize = 22f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        })

        // Status cards
        val card = { title: String, value: String ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                addView(TextView(this@DashboardActivity).apply {
                    text = title; textSize = 12f; setTextColor(Color.GRAY)
                })
                addView(TextView(this@DashboardActivity).apply {
                    text = value; textSize = 16f; setTextColor(Color.WHITE)
                })
            }
        }

        root.addView(card("Active Goal", prefs.getString("active_goal", "None - Start by uploading a file or chatting with AI") ?: "None"))
        root.addView(card("Agent Status", "8 parallel engines ready"))
        root.addView(card("Sandbox", if (File(filesDir, "sandbox").exists()) "Active" else "Ready"))
        root.addView(card("GitHub", prefs.getString("github_status", "Not connected") ?: "Not connected"))
        root.addView(card("IDA MCP", prefs.getString("ida_status", "Built-in server ready") ?: "Built-in server ready"))

        val recent = prefs.getStringSet("recent_files", emptySet())?.toList() ?: emptyList()
        root.addView(card("Recent Files", if (recent.isEmpty()) "No files yet" else recent.take(5).joinToString("\n") { "- ${it.substringAfterLast("/")}" }))

        val success = prefs.getInt("success_count", 0)
        val fail = prefs.getInt("fail_count", 0)
        val rate = if (success + fail > 0) (success * 100 / (success + fail)) else 0
        root.addView(card("Success Rate", "${rate}% (${success}/${success + fail})"))
        root.addView(card("AI Suggestion", "Try uploading an APK or ELF file for autonomous analysis"))

        // Action buttons grid
        val grid = GridLayout(this).apply {
            columnCount = 2
            setPadding(32, 32, 32, 32)
        }

        val btn = { label: String, action: () -> Unit ->
            Button(this).apply {
                text = label
                setOnClickListener { action() }
            }
        }

        grid.addView(btn("AI Chat") { startActivity(Intent(this, AIAgentChatActivity::class.java)) })
        grid.addView(btn("Plugins") { startActivity(Intent(this, PluginManagerActivity::class.java)) })
        grid.addView(btn("Agents") { startActivity(Intent(this, AgentStatusActivity::class.java)) })
        grid.addView(btn("Terminal") { startActivity(Intent(this, TerminalActivity::class.java)) })
        grid.addView(btn("Browser") { startActivity(Intent(this, BrowserActivity::class.java)) })
        grid.addView(btn("Logs") {
            val logs = AuditLogManager(this).getRecentLogs(20)
            AlertDialog.Builder(this)
                .setTitle("Recent Audit Logs")
                .setMessage(if (logs.isEmpty()) "No logs yet." else logs.joinToString("\n\n") { "[${it.time}] ${it.actor}: ${it.action} -> ${it.result}" })
                .setPositiveButton("OK", null)
                .show()
        })

        root.addView(grid)

        root.addView(Button(this).apply {
            text = "Back to Main"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }
}
