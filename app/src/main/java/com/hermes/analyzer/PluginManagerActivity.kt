package com.hermes.analyzer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.hermes.analyzer.ai.PluginEngine

class PluginManagerActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val enabledPlugins = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)
        setContentView(scroll)
        title = "Plugin Manager"

        val prefs = getSharedPreferences("hermes_plugins", MODE_PRIVATE)
        enabledPlugins.addAll(prefs.getStringSet("enabled", PluginEngine.BUILTIN_PLUGINS.map { it.name }.toSet()) ?: emptySet())

        refreshPluginList()
    }

    private fun refreshPluginList() {
        container.removeAllViews()

        container.addView(TextView(this).apply {
            text = "Plugin Manager (${PluginEngine.BUILTIN_PLUGINS.size} built-in)"
            textSize = 20f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        })

        container.addView(TextView(this).apply {
            text = "Toggle plugins on/off. Disabled plugins will not run during autonomous analysis."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(32, 8, 32, 24)
        })

        PluginEngine.BUILTIN_PLUGINS.forEach { plugin ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 16, 32, 16)
            }

            val sw = Switch(this).apply {
                isChecked = enabledPlugins.contains(plugin.name)
                setOnCheckedChangeListener { _, isOn ->
                    if (isOn) enabledPlugins.add(plugin.name) else enabledPlugins.remove(plugin.name)
                    saveEnabled()
                }
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 0, 0, 0)
                addView(TextView(this@PluginManagerActivity).apply {
                    text = plugin.name
                    textSize = 16f
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@PluginManagerActivity).apply {
                    text = plugin.description
                    textSize = 12f
                    setTextColor(Color.GRAY)
                })
            }

            row.addView(sw)
            row.addView(info)
            container.addView(row)

            // Divider
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.DKGRAY)
            })
        }

        container.addView(Button(this).apply {
            text = "Enable All"
            setOnClickListener {
                enabledPlugins.addAll(PluginEngine.BUILTIN_PLUGINS.map { it.name })
                saveEnabled()
                refreshPluginList()
            }
        })

        container.addView(Button(this).apply {
            text = "Disable All"
            setOnClickListener {
                enabledPlugins.clear()
                saveEnabled()
                refreshPluginList()
            }
        })

        container.addView(Button(this).apply {
            text = "Run All Enabled"
            setOnClickListener {
                Toast.makeText(this@PluginManagerActivity, "Running ${enabledPlugins.size} plugins...", Toast.LENGTH_SHORT).show()
            }
        })

        container.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }

    private fun saveEnabled() {
        getSharedPreferences("hermes_plugins", MODE_PRIVATE).edit()
            .putStringSet("enabled", enabledPlugins)
            .apply()
    }
}
