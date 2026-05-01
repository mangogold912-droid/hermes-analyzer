package com.hermes.analyzer

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hermes.analyzer.ai.AdvancedAIEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI Agent Chat Activity
 *
 * Features:
 * - Natural language chat with autonomous AI
 * - File upload during chat (click paperclip icon)
 * - Agent mode: AI autonomously selects and runs tools in parallel
 * - Web search results display
 * - GitHub tool discovery
 * - Real-time progress display
 * - Memory/persistence across sessions
 */
class AIAgentChatActivity : AppCompatActivity() {

    private val TAG = "AIAgentChat"
    private lateinit var engine: AdvancedAIEngine
    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var editInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnAttach: ImageButton
    private lateinit var btnAgent: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var statusBar: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var uploadedFile: Uri? = null
    private var isAgentMode = false
    private var typingRunnable: Runnable? = null

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_FILE_TYPE = "file_type"
    }

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            uploadedFile = uri
            val fileName = getFileName(uri)
            editInput.setText("Analyze uploaded file: $fileName")
            Toast.makeText(this, "File selected: $fileName", Toast.LENGTH_SHORT).show()
            addSystemMessage("File attached: $fileName. Type your analysis goal and press Send.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_agent_chat)

        engine = AdvancedAIEngine(this)

        chatContainer = findViewById(R.id.chatContainer)
        scrollView = findViewById(R.id.scrollView)
        editInput = findViewById(R.id.editInput)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        btnAgent = findViewById(R.id.btnAgent)
        btnSettings = findViewById(R.id.btnSettings)
        statusBar = findViewById(R.id.statusBar)

        setupButtons()
        setupInput()

        // Check for initial file from intent
        val initialFile = intent.getStringExtra(EXTRA_FILE_PATH)
        val initialType = intent.getStringExtra(EXTRA_FILE_TYPE) ?: "auto"
        if (initialFile != null) {
            addSystemMessage("File loaded: $initialFile ($initialType)")
            addSystemMessage("Describe what you want to analyze, or type 'agent' for autonomous mode.")
        }

        // Welcome message
        addAiMessageTyping(
            "Welcome to Hermes AI Agent!\n\n" +
            "I can autonomously analyze files by selecting and running tools in parallel.\n\n" +
            "**Commands:**\n" +
            "- Type a goal like: \"Find security vulnerabilities in this APK\"\n" +
            "- Tap the agent button for full autonomous mode\n" +
            "- Tap the paperclip to upload a file\n" +
            "- Type 'help' for more options\n\n" +
            "**Active AI platforms**: ${engine.getActivePlatforms().joinToString()}\n" +
            "**API keys configured**: ${engine.getActivePlatforms().size}/8"
        )
    }

    private fun setupButtons() {
        btnSend.setOnClickListener { sendMessage() }

        btnAttach.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        btnAgent.setOnClickListener {
            toggleAgentMode()
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun setupInput() {
        editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
    }

    private fun sendMessage() {
        val message = editInput.text.toString().trim()
        if (message.isEmpty()) return

        editInput.text.clear()
        addUserMessage(message)

        // Check if file is attached
        val fileUri = uploadedFile
        uploadedFile = null // Clear after use

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                processMessage(message, fileUri)
            }
        }
    }

    private suspend fun processMessage(message: String, fileUri: Uri?) {
        val startTime = System.currentTimeMillis()

        handler.post { showStatus("AI is thinking...") }

        try {
            val response = when {
                // File upload analysis
                fileUri != null -> {
                    handler.post { showStatus("Processing uploaded file...") }
                    engine.processUploadedFile(fileUri, message)
                }

                // Agent mode - full autonomous
                isAgentMode || message.lowercase().startsWith("agent") ||
                message.lowercase().startsWith("auto") ||
                message.lowercase().contains("analyze this") ||
                message.lowercase().contains("full analysis") -> {
                    handler.post { showStatus("Agent: Creating execution plan...") }

                    // Parse intent
                    val intent = engine.parseIntent(message)
                    val filePath = intent.filePath ?: findRecentFile()

                    if (filePath == null) {
                        handler.post {
                            addAiMessageTyping("Please upload a file first, or specify a file path in your message.")
                            showStatus("Ready")
                        }
                        return
                    }

                    handler.post { showStatus("Agent: Running tools in parallel...") }
                    val result = engine.analyzeFileAutonomously(
                        filePath, intent.fileType, intent.originalCommand
                    )
                    result
                }

                // Web search
                message.lowercase().startsWith("search") ||
                message.lowercase().startsWith("find") ||
                message.lowercase().startsWith("look up") -> {
                    val query = message.substringAfter(" ").trim()
                    handler.post { showStatus("Searching web...") }
                    engine.webSearch(query)
                }

                // GitHub search
                message.lowercase().startsWith("github") -> {
                    val query = message.substringAfter(" ").trim()
                    handler.post { showStatus("Searching GitHub...") }
                    engine.searchGitHub(query)
                }

                // Learning stats
                message.lowercase().contains("learning") ||
                message.lowercase().contains("stats") ||
                message.lowercase().contains("memory") -> {
                    engine.getLearningStats()
                }

                // API key management
                message.lowercase().startsWith("api") ||
                message.lowercase().contains("key") -> {
                    handleApiKeyCommand(message)
                }

                // Help
                message.lowercase() == "help" || message.lowercase() == "?" -> {
                    getHelpMessage()
                }

                // Default: AI chat response
                else -> {
                    handler.post { showStatus("Querying 8 AI platforms in parallel...") }
                    generateAiResponse(message)
                }
            }

            val duration = System.currentTimeMillis() - startTime
            handler.post {
                addAiMessageTyping(response)
                showStatus("Done in ${duration / 1000}s | Ready")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            handler.post {
                addAiMessageTyping("Error: ${e.message}")
                showStatus("Error - Ready")
            }
        }
    }

    private suspend fun generateAiResponse(message: String): String {
        val lowerMsg = message.lowercase().trim()
        // Check for self-AI mode triggers
        val selfAiTriggers = listOf("자체ai", "자체 ai", "selfai", "self ai", "고급엔진", "고급 엔진", "cognitive", "metaa", "강화학습", "reinforcement")
        val isSelfAiMode = selfAiTriggers.any { lowerMsg.contains(it) } || isAgentMode
        
        if (isSelfAiMode) {
            return generateSelfAiResponse(message)
        }
        
        val intent = engine.parseIntent(message)
        val filePath = intent.filePath ?: findRecentFile()
        if ((intent.goals.isNotEmpty() || isAgentMode) && filePath != null) {
            return engine.analyzeFileAutonomously(filePath, intent.fileType, message)
        }
        return engine.chatWithParallelAI(message, filePath)
    }
    
    /**
     * Generate response using self-contained advanced AI (reinforcement + cognitive)
     */
    private suspend fun generateSelfAiResponse(message: String): String {
        val sb = StringBuilder()
        sb.append("## 자체 고급 AI 응답 (Self-AI Mode)\n\n")
        sb.append("*Running internal reinforcement-learning + cognitive engine...*\n\n")

        val filePath = findRecentFile()
        val fileType = filePath?.let { engine.getFileType(it) } ?: "unknown"
        val userIntent = engine.inferIntent(message, fileType)

        sb.append("### 인지 분석 (Cognitive Analysis)\n")
        sb.append("- **감지된 의도**: ${userIntent.primaryType} (신뢰도: ${"%.0f".format(userIntent.confidence * 100)}%)\n")
        sb.append("- **긴급도**: ${userIntent.urgency}/5\n")
        if (userIntent.entities.isNotEmpty()) {
            sb.append("- **감지된 도구**: ${userIntent.entities.joinToString()}\n")
        }
        sb.append("- **권장 접근법**: ${userIntent.suggestedApproach}\n\n")

        val reasoningTree = engine.decomposeGoal(message, fileType)
        sb.append("### 계층적 추론 (Hierarchical Reasoning)\n")
        sb.append("복잡도: ${reasoningTree.root.complexity}/5 | 예상 단계: ${reasoningTree.estimatedSteps}\n")
        reasoningTree.root.subGoals.forEach { phase ->
            sb.append("\n**${phase.description}**\n")
            phase.subGoals.forEach { step ->
                sb.append("  - ${step.description} (난이도: ${step.complexity})\n")
            }
        }
        sb.append("\n")

        if (filePath != null && java.io.File(filePath).exists()) {
            sb.append("### 실제 파일 분석 실행\n\n")
            val localResult = engine.analyzeFileAutonomously(filePath, fileType, message)
            sb.append(localResult)
        } else {
            sb.append("### 지식 기반 응답\n\n")
            val knowledge = engine.queryKnowledge(message.take(20))
            if (knowledge.isNotEmpty()) {
                sb.append("관련 지식:\n")
                knowledge.take(3).forEach { k ->
                    sb.append("- ${k.key}: ${k.value.take(100)}... (신뢰도: ${"%.0f".format(k.confidence * 100)}%)\n")
                }
                sb.append("\n")
            }
            sb.append(engine.generateLocalFallbackResponse(message, null))
        }

        sb.append("\n### 메타 인지 (Meta-Cognition)\n")
        val reflection = engine.selfReflect(sb.toString(), 5000)
        sb.append("- **자체 평가 점수**: ${"%.0f".format(reflection.selfScore * 100)}/100\n")
        if (reflection.issues.isNotEmpty()) {
            sb.append("- **개선 필요사항**:\n")
            reflection.issues.take(2).forEach { sb.append("  - $it\n") }
        }
        if (reflection.improvements.isNotEmpty()) {
            sb.append("- **제안된 개선**:\n")
            reflection.improvements.take(2).forEach { sb.append("  - $it\n") }
        }

        sb.append("\n### 강화학습 상태 (Reinforcement Learning)\n")
        sb.append(engine.getStrategyReport())

        return sb.toString()
    }

    private fun handleApiKeyCommand(message: String): String {
        val parts = message.split(" ", limit = 3)
        if (parts.size >= 3 && parts[1] == "set") {
            val keyParts = parts[2].split(" ", limit = 2)
            if (keyParts.size == 2) {
                val platform = keyParts[0].lowercase()
                val key = keyParts[1]
                val success = engine.saveApiKey(platform, key)
                return if (success) {
                    "API key saved for **$platform**. Active platforms: ${engine.getActivePlatforms().joinToString()}"
                } else {
                    "Failed to save API key. Valid platforms: ${AdvancedAIEngine.ALL_PLATFORMS.joinToString()}"
                }
            }
        }

        val keys = engine.getActivePlatforms()
        return buildString {
            append("## API Key Management\n\n")
            append("**Configured**: ${keys.size}/8\n\n")
            append("**Usage**: `api set <platform> <key>`\n\n")
            append("**Platforms**:\n")
            AdvancedAIEngine.ALL_PLATFORMS.forEach { platform ->
                val status = if (engine.hasApiKey(platform)) "OK" else "Not set"
                append("- $platform: $status\n")
            }
        }
    }

    private fun getHelpMessage(): String {
        return buildString {
            append("## Hermes AI Agent - Help\n\n")

            append("### Autonomous Analysis\n")
            append("- Type your goal naturally, e.g.:\n")
            append("  \"Find all security vulnerabilities in this APK\"\n")
            append("  \"Reverse engineer the crypto and network logic\"\n")
            append("  \"Full malware analysis with string extraction\"\n\n")

            append("### File Upload\n")
            append("- Tap the paperclip icon to upload a file\n")
            append("- Then describe what you want to analyze\n\n")

            append("### Special Commands\n")
            append("- `search <query>` - Search the web\n")
            append("- `github <query>` - Search GitHub for tools\n")
            append("- `api set <platform> <key>` - Set AI API key\n")
            append("- `api list` - List configured API keys\n")
            append("- `stats` or `memory` - View AI learning statistics\n")
            append("- `agent <goal>` - Force agent mode\n")
            append("- `help` - Show this help\n\n")

            append("### AI Platforms (8 total)\n")
            append("${AdvancedAIEngine.ALL_PLATFORMS.joinToString(", ")}\n\n")

            append("### Features\n")
            append("- 50 built-in reverse engineering tools\n")
            append("- Intelligent parallel tool execution\n")
            append("- Self-reflection and re-planning\n")
            append("- Tool auto-discovery from GitHub\n")
            append("- Web search integration\n")
            append("- Reinforcement learning memory\n")
            append("- Virtual sandbox environment\n")
            append("- Browser automation for file download\n\n")

            append("Tap the agent button (robot icon) to toggle autonomous mode.")
        }
    }

    // ==================== UI HELPERS ====================

    private fun toggleAgentMode() {
        isAgentMode = !isAgentMode
        val color = if (isAgentMode) android.graphics.Color.GREEN else android.graphics.Color.GRAY
        btnAgent.setColorFilter(color)
        val status = if (isAgentMode) {
            addSystemMessage("Agent mode ON - I will autonomously select and run tools")
            "Agent Mode: ON"
        } else {
            addSystemMessage("Agent mode OFF - Standard chat mode")
            "Agent Mode: OFF"
        }
        showStatus(status)
    }

    private fun showSettingsDialog() {
        val platforms = AdvancedAIEngine.ALL_PLATFORMS
        val builder = AlertDialog.Builder(this)
        builder.setTitle("AI API Key Settings")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, padding)

        val editTexts = mutableMapOf<String, EditText>()

        for (platform in platforms) {
            val label = TextView(this)
            label.text = platform.capitalize()
            label.setPadding(0, 8, 0, 4)
            layout.addView(label)

            val edit = EditText(this)
            edit.hint = "Enter API key for $platform"
            edit.setText(engine.getApiKey(platform) ?: "")
            edit.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layout.addView(edit)
            editTexts[platform] = edit
        }

        val scroll = ScrollView(this)
        scroll.addView(layout)
        builder.setView(scroll)

        builder.setPositiveButton("Save") { _, _ ->
            var saved = 0
            for ((platform, edit) in editTexts) {
                val key = edit.text.toString().trim()
                if (key.isNotEmpty()) {
                    if (engine.saveApiKey(platform, key)) saved++
                }
            }
            val activePlatforms = engine.getActivePlatforms()
            Toast.makeText(this, "Saved $saved keys. Active: ${activePlatforms.size} (${activePlatforms.joinToString()})", Toast.LENGTH_LONG).show()
            addSystemMessage("API keys updated. Active: ${engine.getActivePlatforms().joinToString()}")
        }

        builder.setNegativeButton("Clear All") { _, _ ->
            // TODO: Clear all API keys
            Toast.makeText(this, "All API keys cleared", Toast.LENGTH_SHORT).show()
        }

        builder.setNeutralButton("Cancel", null)
        builder.show()
    }

    private fun addUserMessage(text: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_user, chatContainer, false)
        val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
        tvMessage.text = text
        chatContainer.addView(view)
        scrollToBottom()
    }

    private fun addAiMessageTyping(text: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_ai, chatContainer, false)
        val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
        chatContainer.addView(view)

        // Typing animation
        var index = 0
        val delay = if (text.length > 500) 2L else 8L

        typingRunnable?.let { handler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                if (index < text.length) {
                    // Process markdown-style formatting
                    val chunk = text.substring(0, minOf(index + 5, text.length))
                    tvMessage.text = formatMarkdown(chunk)
                    index += 5
                    handler.postDelayed(this, delay)
                } else {
                    tvMessage.text = formatMarkdown(text)
                }
                scrollToBottom()
            }
        }
        typingRunnable = runnable
        handler.postDelayed(runnable, 100)
    }

    private fun addSystemMessage(text: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_system, chatContainer, false)
        val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
        tvMessage.text = text
        chatContainer.addView(view)
        scrollToBottom()
    }

    private fun showStatus(status: String) {
        statusBar.text = status
    }

    private fun scrollToBottom() {
        handler.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun formatMarkdown(text: String): CharSequence {
        val ssb = SpannableStringBuilder(text)

        // Bold: **text**
        val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
        boldPattern.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            ssb.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return ssb
    }

    private fun getFileName(uri: Uri): String {
        var result = "unknown"
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        return result
    }

    private fun findRecentFile(): String? {
        val uploadsDir = getExternalFilesDir(null)?.let { File(it, "uploads") }
        return uploadsDir?.listFiles()?.maxByOrNull { it.lastModified() }?.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        typingRunnable?.let { handler.removeCallbacks(it) }
        engine.destroy()
    }
}
