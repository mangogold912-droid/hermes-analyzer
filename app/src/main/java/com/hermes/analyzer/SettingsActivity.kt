package com.hermes.analyzer

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hermes.analyzer.ai.AIMultiEngine

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(container)

        val aiEngine = AIMultiEngine(this)
        val prefs: SharedPreferences = getSharedPreferences("hermes_settings", MODE_PRIVATE)

        container.addView(TextView(this).apply {
            text = "AI Platform API Keys"
            textSize = 20f
            setPadding(16, 32, 16, 16)
        })

        aiEngine.getPlatforms().forEach { platform ->
            container.addView(TextView(this).apply {
                text = platform.displayName
                setPadding(16, 16, 16, 8)
            })

            val etKey = EditText(this).apply {
                hint = "Enter API Key"
                setText(prefs.getString("key_${platform.name}", "") ?: "")
            }
            container.addView(etKey)

            container.addView(Switch(this).apply {
                text = "Enabled"
                isChecked = platform.enabled
                setOnCheckedChangeListener { _, checked ->
                    aiEngine.setEnabled(platform.name, checked)
                    prefs.edit().putBoolean("enabled_${platform.name}", checked).apply()
                }
            })

            etKey.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) prefs.edit().putString("key_${platform.name}", etKey.text.toString()).apply()
            }
        }

        container.addView(TextView(this).apply {
            text = "IDA Pro MCP Server"
            textSize = 20f
            setPadding(16, 32, 16, 16)
        })

        val etIdaHost = EditText(this).apply {
            hint = "Host (default: 192.168.1.100)"
            setText(prefs.getString("ida_host", "192.168.1.100"))
        }
        container.addView(etIdaHost)

        val etIdaPort = EditText(this).apply {
            hint = "Port (default: 8080)"
            setText(prefs.getInt("ida_port", 8080).toString())
        }
        container.addView(etIdaPort)

        container.addView(Button(this).apply {
            text = "Save Settings"
            setOnClickListener {
                prefs.edit().apply {
                    putString("ida_host", etIdaHost.text.toString())
                    putInt("ida_port", etIdaPort.text.toString().toIntOrNull() ?: 8080)
                    apply()
                }
                Toast.makeText(this@SettingsActivity, "Saved!", Toast.LENGTH_SHORT).show()
            }
        })

        container.addView(Button(this).apply {
            text = "Clear All Data"
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Clear All Data")
                    .setMessage("Delete all analysis history?")
                    .setPositiveButton("Yes") { _, _ ->
                        val db = com.hermes.analyzer.db.AnalysisDatabase(this@SettingsActivity)
                        db.clearAll()
                        Toast.makeText(this@SettingsActivity, "Cleared!", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        })
    }
}
