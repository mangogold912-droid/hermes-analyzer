package com.hermes.analyzer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hermes.analyzer.ai.AdvancedAIEngine

class AgentStatusActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)
        title = "Agent Status"

        root.addView(TextView(this).apply {
            text = "Parallel AI Agent Pool (8)"
            textSize = 20f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        })

        val agents = listOf(
            Triple("Agent 1: Code Analyzer", "Analyzes source code, decompilation output, and static analysis results.", "Ready"),
            Triple("Agent 2: Security Inspector", "Scans for vulnerabilities, crypto misuses, and security anti-patterns.", "Ready"),
            Triple("Agent 3: Documentation AI", "Summarizes findings, generates reports, and creates markdown documentation.", "Ready"),
            Triple("Agent 4: Web Scout", "Searches the web for tool documentation, CVE info, and latest vulnerabilities.", "Ready"),
            Triple("Agent 5: Test Runner", "Executes test scripts, validates outputs, and checks correctness.", "Ready"),
            Triple("Agent 6: Binary Analyst", "Handles ELF/APK/DEX disassembly, string analysis, and header parsing.", "Ready"),
            Triple("Agent 7: GitHub Operator", "Clones repos, reads files, creates branches/commits/PRs via GitHub API.", "Ready"),
            Triple("Agent 8: Result Validator", "Cross-checks all agent outputs for consistency and final report synthesis.", "Ready")
        )

        agents.forEach { (name, desc, status) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
                setBackgroundColor(Color.parseColor("#1A1A2E"))
            }
            card.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(Color.parseColor("#00D4AA"))
            })
            card.addView(TextView(this).apply {
                text = desc
                textSize = 12f
                setTextColor(Color.LTGRAY)
                setPadding(0, 4, 0, 8)
            })
            card.addView(TextView(this).apply {
                text = "Status: $status"
                textSize = 12f
                setTextColor(if (status == "Ready") Color.GREEN else Color.YELLOW)
            })
            root.addView(card)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
            })
        }

        root.addView(TextView(this).apply {
            text = "All 8 agents are operational. During autonomous analysis, tasks are distributed across agents and results are merged by the main AI orchestrator."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(32, 16, 32, 16)
        })

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }
}
