package com.hermes.analyzer

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hermes.analyzer.db.AnalysisDatabase
import org.json.JSONObject

class AnalysisResultsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val jobId = intent.getLongExtra("jobId", 0)
        val db = AnalysisDatabase(this)
        val results = db.getResultsForJob(jobId)

        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 13f
        }

        val sb = StringBuilder()
        if (results.isEmpty()) {
            sb.append("No results found for job $jobId")
        } else {
            results.forEach { result ->
                sb.appendLine("=== ${result.platformName.toUpperCase()} ===")
                try {
                    val json = JSONObject(result.content)
                    sb.appendLine("Summary: ${json.optString("summary", "N/A")}")
                    sb.appendLine("Risk: ${json.optInt("riskScore", 0)}/100 | Confidence: ${(result.confidence * 100).toInt()}%")
                    val vulns = json.optJSONArray("vulnerabilities")
                    if (vulns != null && vulns.length() > 0) {
                        sb.appendLine("Vulnerabilities:")
                        for (i in 0 until vulns.length()) {
                            val v = vulns.getJSONObject(i)
                            sb.appendLine("  [${v.optString("severity")}] ${v.optString("type")}")
                        }
                    }
                    val patterns = json.optJSONArray("detectedPatterns")
                    if (patterns != null && patterns.length() > 0) {
                        sb.appendLine("Patterns: ${(0 until patterns.length()).map { patterns.getString(it) }.joinToString(", ")}")
                    }
                    val decompiled = json.optString("decompiledInsights", "")
                    if (decompiled.isNotEmpty()) sb.appendLine("Insights: $decompiled")
                    val usage = json.optString("usageContext", "")
                    if (usage.isNotEmpty()) sb.appendLine("Usage: $usage")
                } catch (_: Exception) {
                    sb.appendLine(result.rawText.take(300))
                }
                sb.appendLine()
            }
        }
        tv.text = sb.toString()
        scroll.addView(tv)
        setContentView(scroll)
    }
}
