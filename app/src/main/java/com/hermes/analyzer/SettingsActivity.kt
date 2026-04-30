package com.hermes.analyzer

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hermes.analyzer.ai.AIMultiEngine

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val aiEngine = AIMultiEngine(this)
        val prefs = getSharedPreferences("hermes_settings", MODE_PRIVATE)
        val container = findViewById<LinearLayout>(R.id.containerSettings)

        // Title
        val tvTitle = TextView(this).apply {
            text = "AI Platform API Keys"
            textSize = 20f
            setPadding(0, 16, 0, 16)
        }
        container.addView(tvTitle)

        // API Key inputs for each platform
        aiEngine.getPlatforms().forEach { platform ->
            val tvLabel = TextView(this).apply {
                text = platform.displayName
                textSize = 16f
                setPadding(0, 16, 0, 8)
            }
            container.addView(tvLabel)

            val etKey = EditText(this).apply {
                hint = "Enter API Key"
                setText(prefs.getString("key_${platform.name}", "") ?: "")
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        prefs.edit().putString("key_${platform.name}", text.toString()).apply()
                    }
                }
            }
            container.addView(etKey)

            val switch = Switch(this).apply {
                text = "Enabled"
                isChecked = platform.enabled
                setOnCheckedChangeListener { _, checked ->
                    aiEngine.setEnabled(platform.name, checked)
                }
            }
            container.addView(switch)
        }

        // IDA Pro Settings
        val tvIda = TextView(this).apply {
            text = "IDA Pro MCP Server"
            textSize = 20f
            setPadding(0, 32, 0, 16)
        }
        container.addView(tvIda)

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

        // Save button
        val btnSave = Button(this).apply {
            text = "Save Settings"
            setOnClickListener {
                prefs.edit().apply {
                    putString("ida_host", etIdaHost.text.toString())
                    putInt("ida_port", etIdaPort.text.toString().toIntOrNull() ?: 8080)
                    apply()
                }
                Toast.makeText(this@SettingsActivity, "Settings saved!", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(btnSave)

        // Clear data button
        val btnClear = Button(this).apply {
            text = "Clear All Data"
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Clear All Data")
                    .setMessage("Delete all analysis history?")
                    .setPositiveButton("Yes") { _, _ ->
                        val db = com.hermes.analyzer.db.AnalysisDatabase(this@SettingsActivity)
                        db.clearAll()
                        Toast.makeText(this@SettingsActivity, "All data cleared!", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        }
        container.addView(btnClear)
    }
}
