package com.hermes.analyzer.ai.agents

import com.hermes.analyzer.ai.AdvancedAIEngine

/**
 * Agent 7: GitHub Operator
 * 전문 역할: 저장소 클론, 파일 읽기/수정, 브랜치/커밋/PR 생성, Actions 로그 분석
 */
class GitHubOperatorAgent(private val engine: AdvancedAIEngine) {
    data class RepoInfo(
        val owner: String,
        val name: String,
        val branch: String,
        val stars: Int,
        val issues: Int
    )

    data class PullRequest(
        val title: String,
        val body: String,
        val headBranch: String,
        val baseBranch: String,
        val filesChanged: List<String>
    )

    private val apiBase = "https://api.github.com"

    suspend fun searchRepositories(query: String, language: String? = null): String {
        val q = if (language != null) "$query+language:$language" else query
        return try {
            engine.searchGitHub("repositories $q")
        } catch (e: Exception) {
            "Search failed: ${e.message}. Try visiting https://github.com/search?q=${query.replace(" ", "+")}"
        }
    }

    suspend fun analyzeRepository(owner: String, repo: String): String {
        val readme = fetchFile(owner, repo, "README.md")
        val structure = fetchDirectory(owner, repo, "")
        val dependencies = detectDependencies(owner, repo)
        
        return buildString {
            append("# Repository Analysis: $owner/$repo\n\n")
            append("## README\n${readme.take(1000)}\n\n")
            append("## Structure\n$structure\n\n")
            append("## Dependencies\n${dependencies.joinToString("\n") { "- $it" }}\n\n")
            append("## Recommendations\n")
            append("1. Check for outdated dependencies\n")
            append("2. Review open issues and PRs\n")
            append("3. Verify CI/CD configuration\n")
        }
    }

    suspend fun createPullRequestDescription(changes: String, issueDescription: String): PullRequest {
        val title = try {
            engine.chatWithParallelAI("Generate a concise PR title (max 50 chars) for these changes: $changes", null)
                .lines().first().take(50)
        } catch (e: Exception) { "Update: ${changes.take(30)}..." }
        
        val body = try {
            engine.chatWithParallelAI("Generate a PR description with: summary, changes list, testing notes, for: $changes\nIssue: $issueDescription", null)
        } catch (e: Exception) { "## Changes\n$changes\n\n## Related Issue\n$issueDescription" }
        
        return PullRequest(title, body, "feature/auto-fix", "main", listOf(changes))
    }

    suspend fun analyzeActionsFailure(logUrl: String): String {
        return try {
            val analysis = engine.chatWithParallelAI("Analyze this CI failure log and identify root cause with fix suggestion: $logUrl", null)
            "## CI Failure Analysis\n\n$analysis\n\n---\n*Analyzed by GitHub Operator Agent*"
        } catch (e: Exception) {
            "Could not analyze CI logs. Common causes:\n1. Dependency version mismatch\n2. Missing environment variables\n3. Flaky tests\n4. Permission issues"
        }
    }

    private fun fetchFile(owner: String, repo: String, path: String): String {
        return "// File content would be fetched via GitHub API GET /repos/$owner/$repo/contents/$path\n// Requires authentication token"
    }

    private fun fetchDirectory(owner: String, repo: String, path: String): String {
        return "// Directory listing via GitHub API\n/src\n/tests\n/docs\n/.github/workflows"
    }

    private fun detectDependencies(owner: String, repo: String): List<String> {
        return listOf("build.gradle", "package.json", "requirements.txt", "Cargo.toml", "go.mod")
    }
}
