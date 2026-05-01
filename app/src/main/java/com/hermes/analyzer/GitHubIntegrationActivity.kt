package com.hermes.analyzer

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hermes.analyzer.ai.agents.GitHubOperatorAgent
import com.hermes.analyzer.ai.AdvancedAIEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GitHubIntegrationActivity
 * GitHub 연동 화면 (토큰 관리, 저장소 검색, PR 생성)
 */
class GitHubIntegrationActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var engine: AdvancedAIEngine
    private lateinit var github: GitHubOperatorAgent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)
        title = "GitHub Integration"

        prefs = getSharedPreferences("hermes_github", MODE_PRIVATE)
        engine = AdvancedAIEngine(this)
        github = GitHubOperatorAgent(engine)

        refresh(root)
    }

    private fun refresh(root: LinearLayout) {
        root.removeAllViews()

        root.addView(TextView(this).apply {
            text = "GitHub Integration"
            textSize = 20f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        })

        // Token status
        val token = prefs.getString("github_token", "")
        root.addView(TextView(this).apply {
            text = if (token.isNullOrEmpty()) "Status: Not Connected" else "Status: Connected ✓"
            textSize = 14f
            setTextColor(if (token.isNullOrEmpty()) Color.RED else Color.parseColor("#00D4AA"))
            setPadding(32, 16, 32, 16)
        })

        // Token input
        val etToken = EditText(this).apply {
            hint = "GitHub Personal Access Token (ghp_...)"
            if (!token.isNullOrEmpty()) setText(token)
        }
        root.addView(etToken)

        root.addView(Button(this).apply {
            text = "Save Token"
            setOnClickListener {
                prefs.edit().putString("github_token", etToken.text.toString()).apply()
                Toast.makeText(this@GitHubIntegrationActivity, "Token saved", Toast.LENGTH_SHORT).show()
                refresh(root)
            }
        })

        // Repository search
        val etRepo = EditText(this).apply { hint = "Search repositories (e.g. okhttp, retrofit)" }
        root.addView(etRepo)
        root.addView(Button(this).apply {
            text = "Search Repositories"
            setOnClickListener {
                val query = etRepo.text.toString()
                if (query.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val result = github.searchRepositories(query)
                        withContext(Dispatchers.Main) {
                            AlertDialog.Builder(this@GitHubIntegrationActivity)
                                .setTitle("Search Results")
                                .setMessage(result.take(2000))
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
        })

        // Quick actions
        root.addView(TextView(this).apply {
            text = "Quick Actions"
            textSize = 16f
            setTextColor(Color.parseColor("#00D4AA"))
            setPadding(32, 24, 32, 8)
        })

        val actions = listOf("My Repositories", "Starred Repos", "Issues", "Pull Requests", "Actions Logs")
        actions.forEach { action ->
            root.addView(Button(this).apply {
                text = action
                setOnClickListener {
                    Toast.makeText(this@GitHubIntegrationActivity, "$action: Loading...", Toast.LENGTH_SHORT).show()
                }
            })
        }

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }
}
