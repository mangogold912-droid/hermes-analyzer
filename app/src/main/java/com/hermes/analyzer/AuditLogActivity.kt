package com.hermes.analyzer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class AuditLogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)
        title = "Audit Logs"

        val audit = AuditLogManager(this)
        val logs = audit.getRecentLogs(100)

        root.addView(TextView(this).apply {
            text = "Security Audit Logs"
            textSize = 20f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "${logs.size} entries (max 500 stored)"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(32, 8, 32, 16)
        })

        if (logs.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No audit logs yet. Logs are generated automatically when AI agents and tools run."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(32, 16, 32, 16)
            })
        }

        logs.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
                setBackgroundColor(Color.parseColor("#1E1E1E"))
            }
            val color = when (entry.result) {
                "success" -> Color.GREEN
                "error" -> Color.RED
                else -> Color.YELLOW
            }
            card.addView(TextView(this@AuditLogActivity).apply {
                text = "[${entry.time}] ${entry.actor} → ${entry.action}"
                textSize = 12f
                setTextColor(color)
            })
            if (entry.command.isNotEmpty()) {
                card.addView(TextView(this@AuditLogActivity).apply {
                    text = "Cmd: ${entry.command.take(80)}"
                    textSize = 11f
                    setTextColor(Color.GRAY)
                })
            }
            if (entry.filesChanged.isNotEmpty()) {
                card.addView(TextView(this@AuditLogActivity).apply {
                    text = "Files: ${entry.filesChanged.joinToString()}"
                    textSize = 11f
                    setTextColor(Color.GRAY)
                })
            }
            root.addView(card)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 4)
            })
        }

        root.addView(Button(this).apply {
            text = "Export Logs"
            setOnClickListener {
                val exported = audit.export()
                Toast.makeText(this@AuditLogActivity, "Exported ${exported.length} chars", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "Clear Logs"
            setOnClickListener {
                audit.clear()
                Toast.makeText(this@AuditLogActivity, "Cleared", Toast.LENGTH_SHORT).show()
                recreate()
            }
        })

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }
}
