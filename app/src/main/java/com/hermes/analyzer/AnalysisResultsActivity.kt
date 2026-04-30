package com.hermes.analyzer

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hermes.analyzer.db.AnalysisDatabase
import org.json.JSONObject

class AnalysisResultsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val jobId = intent.getLongExtra("jobId", 0)
        val db = AnalysisDatabase(this)
        val tvResults = findViewById<TextView>(R.id.tvResults)

        val results = db.getResultsForJob(jobId)
        val sb = StringBuilder()

        results.forEach { result ->
            sb.appendLine("=== ${result.platformName.uppercase()} ===")
            try {
                val json = JSONObject(result.content)
                sb.appendLine("Summary: ${json.optString("summary", "N/A")}")
                sb.appendLine("Risk Score: ${json.optInt("riskScore", 0)}/100")
                sb.appendLine("Confidence: ${(result.confidence * 100).toInt()}%")
                sb.appendLine("Time: ${result.processingTime}ms")

                val vulns = json.optJSONArray("vulnerabilities")
                if (vulns != null && vulns.length() > 0) {
                    sb.appendLine("Vulnerabilities:")
                    for (i in 0 until vulns.length()) {
                        val v = vulns.getJSONObject(i)
                        sb.appendLine("  - [${v.optString("severity")}] ${v.optString("type")}: ${v.optString("description")}")
                    }
                }

                val patterns = json.optJSONArray("detectedPatterns")
                if (patterns != null && patterns.length() > 0) {
                    sb.appendLine("Patterns:")
                    for (i in 0 until patterns.length()) {
                        sb.appendLine("  - ${patterns.getString(i)}")
                    }
                }

                val decompiled = json.optString("decompiledInsights", "")
                if (decompiled.isNotEmpty()) {
                    sb.appendLine("Decompiled Insights: $decompiled")
                }

                val usage = json.optString("usageContext", "")
                if (usage.isNotEmpty()) {
                    sb.appendLine("Usage Context: $usage")
                }

            } catch (_: Exception) {
                sb.appendLine(result.rawText.take(500))
            }
            sb.appendLine()
        }

        tvResults.text = sb.toString()
    }
}
