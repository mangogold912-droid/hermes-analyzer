package com.hermes.analyzer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hermes.analyzer.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SandboxManagerActivity : AppCompatActivity() {
    private lateinit var sandbox: SandboxManager
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)
        setContentView(scroll)
        title = "Sandbox Manager"

        sandbox = SandboxManager(this)
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()

        container.addView(TextView(this).apply {
            text = "Sandbox Manager"
            textSize = 20f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        })

        val sandboxes = sandbox.listSandboxes()
        container.addView(TextView(this).apply {
            text = "Active Sandboxes: ${sandboxes.size}"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(32, 8, 32, 16)
        })

        sandboxes.forEach { box ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
                setBackgroundColor(Color.parseColor("#1A1A2E"))
            }
            card.addView(TextView(this@SandboxManagerActivity).apply {
                text = "${box.name} (${box.type})"
                textSize = 16f
                setTextColor(Color.parseColor("#00D4AA"))
            })
            card.addView(TextView(this@SandboxManagerActivity).apply {
                text = "Status: ${box.status} | ${java.io.File(box.rootDir, "workspace").list()?.size ?: 0} files"
                textSize = 12f
                setTextColor(Color.LTGRAY)
            })

            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val etCommand = EditText(this).apply {
                hint = "Enter command..."
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnRun = Button(this).apply {
                text = "Run"
                setOnClickListener {
                    val cmd = etCommand.text.toString()
                    if (cmd.isNotBlank()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val result = sandbox.execute(box.id, cmd)
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(this@SandboxManagerActivity)
                                    .setTitle("Result (exit=${result.exitCode})")
                                    .setMessage("OUT:\n${result.stdout.take(2000)}\n\nERR:\n${result.stderr.take(1000)}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                }
            }
            btnRow.addView(etCommand)
            btnRow.addView(btnRun)
            card.addView(btnRow)
            container.addView(card)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
            })
        }

        container.addView(Button(this).apply {
            text = "Create New Sandbox"
            setOnClickListener {
                val name = "sandbox-${System.currentTimeMillis()}"
                sandbox.createSandbox(name, "bash")
                Toast.makeText(this@SandboxManagerActivity, "Created $name", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })

        container.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }
}
