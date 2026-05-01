package com.hermes.analyzer.ai

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI AIChatEngine -- memory/context based
 *
 * - SQLite conversation history storage
 * - Context window management (recent N messages)
 * - Plugin integration (AI automatically executes plugins)
 * - 8 AI Platform API key management (SharedPreferences)
 */
class AIChatEngine(context: Context) {

    private val db: ChatDatabase = ChatDatabase(context)
    private val pluginEngine: PluginEngine = PluginEngine(context)
    private val apiKeyManager: ApiKeyManager = ApiKeyManager(context)

    companion object {
        private const val TAG = "AIChatEngine"
        private const val MAX_CONTEXT = 20

        // 8 AI Platform constants
        const val AI_OPENAI = "openai"
        const val AI_KIMI = "kimi"
        const val AI_QWEN = "qwen"
        const val AI_GEMINI = "gemini"
        const val AI_CLAUDE = "claude"
        const val AI_DEEPSEEK = "deepseek"
        const val AI_OLLAMA = "ollama"
        const val AI_SUPRNINJA = "suprninja"

        val ALL_AI_PLATFORMS = listOf(
            AI_OPENAI, AI_KIMI, AI_QWEN, AI_GEMINI,
            AI_CLAUDE, AI_DEEPSEEK, AI_OLLAMA, AI_SUPRNINJA
        )

        fun getPlatformDisplayName(platform: String): String {
            return when (platform) {
                AI_OPENAI -> "OpenAI (GPT-4)"
                AI_KIMI -> "Kimi (Moonshot)"
                AI_QWEN -> "Qwen (Alibaba)"
                AI_GEMINI -> "Gemini (Google)"
                AI_CLAUDE -> "Claude (Anthropic)"
                AI_DEEPSEEK -> "DeepSeek"
                AI_OLLAMA -> "Ollama (Local)"
                AI_SUPRNINJA -> "Suprninja"
                else -> platform
            }
        }
    }

    data class ChatMessage(
        val id: Long = 0,
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val pluginUsed: String? = null
    )

    // ============================================================
    // API KEY MANAGEMENT
    // ============================================================

    /**
     * ApiKeyManager - 8 AI Platform API key storage via SharedPreferences
     */
    class ApiKeyManager(context: Context) {
        private val prefs: SharedPreferences = context.getSharedPreferences(
            "hermes_api_keys", Context.MODE_PRIVATE
        )

        /**
         * Save API key for a platform
         */
        fun saveApiKey(platform: String, apiKey: String): Boolean {
            if (apiKey.isBlank()) return false
            prefs.edit().putString(platform, apiKey.trim()).apply()
            Log.i(TAG, "API key saved for: $platform")
            return true
        }

        /**
         * Get API key for a platform
         */
        fun getApiKey(platform: String): String? {
            val key = prefs.getString(platform, null)
            return if (key.isNullOrBlank()) null else key
        }

        /**
         * Check if API key exists for a platform
         */
        fun hasApiKey(platform: String): Boolean {
            return !prefs.getString(platform, null).isNullOrBlank()
        }

        /**
         * Delete API key for a platform
         */
        fun deleteApiKey(platform: String) {
            prefs.edit().remove(platform).apply()
            Log.i(TAG, "API key deleted for: $platform")
        }

        /**
         * Get all saved API keys (platform -> key map)
         */
        fun getAllSavedKeys(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (platform in ALL_AI_PLATFORMS) {
                val key = prefs.getString(platform, null)
                if (!key.isNullOrBlank()) {
                    map[platform] = key
                }
            }
            return map
        }

        /**
         * Get platforms that have API keys configured
         */
        fun getActivePlatforms(): List<String> {
            return ALL_AI_PLATFORMS.filter { hasApiKey(it) }
        }

        /**
         * Save all 8 API keys at once
         */
        fun saveAllApiKeys(keys: Map<String, String>): Int {
            var saved = 0
            val editor = prefs.edit()
            for ((platform, key) in keys) {
                if (platform in ALL_AI_PLATFORMS && key.isNotBlank()) {
                    editor.putString(platform, key.trim())
                    saved++
                }
            }
            editor.apply()
            Log.i(TAG, "Saved $saved API keys")
            return saved
        }

        /**
         * Clear all API keys
         */
        fun clearAllKeys() {
            prefs.edit().clear().apply()
            Log.i(TAG, "All API keys cleared")
        }
    }

    // ============================================================
    // PUBLIC API KEY METHODS
    // ============================================================

    fun saveApiKey(platform: String, apiKey: String): Boolean {
        return apiKeyManager.saveApiKey(platform, apiKey)
    }

    fun getApiKey(platform: String): String? {
        return apiKeyManager.getApiKey(platform)
    }

    fun hasApiKey(platform: String): Boolean {
        return apiKeyManager.hasApiKey(platform)
    }

    fun deleteApiKey(platform: String) {
        apiKeyManager.deleteApiKey(platform)
    }

    fun getAllSavedApiKeys(): Map<String, String> {
        return apiKeyManager.getAllSavedKeys()
    }

    fun getActiveAiPlatforms(): List<String> {
        return apiKeyManager.getActivePlatforms()
    }

    fun saveAllApiKeys(keys: Map<String, String>): Int {
        return apiKeyManager.saveAllApiKeys(keys)
    }

    fun clearAllApiKeys() {
        apiKeyManager.clearAllKeys()
    }

    // ============================================================
    // CHAT MESSAGING
    // ============================================================

    /**
     * Send message + generate AI response
     */
    fun sendMessage(userMessage: String): ChatMessage {
        db.saveMessage("user", userMessage)
        val context = db.getRecentMessages(MAX_CONTEXT)
        val pluginResult = autoExecutePlugins(userMessage)
        val response = generateResponse(userMessage, context, pluginResult)
        val msg = ChatMessage(role = "assistant", content = response, pluginUsed = pluginResult.firstOrNull())
        db.saveMessage("assistant", response, msg.pluginUsed)
        return msg
    }

    /**
     * Keyword analysis -> plugin auto-execution
     */
    private fun autoExecutePlugins(msg: String): List<String> {
        val results = mutableListOf<String>()
        val lowerMsg = msg.lowercase(Locale.getDefault())

        val keywordMap = mapOf(
            "elf" to listOf("elf_analyzer", "capstone_disasm"),
            "dex" to listOf("dex_decompiler", "jadx_decompiler"),
            "apk" to listOf("deep_scan_apk", "apktool_engine", "mobsf_scanner"),
            "string" to listOf("string_extractor", "capa_floss"),
            "ida" to listOf("ida_mcp_bridge", "ida_objc_types"),
            "radare" to listOf("radare2_wrapper"),
            "r2" to listOf("radare2_wrapper"),
            "yara" to listOf("yara_scanner"),
            "malware" to listOf("yara_scanner", "quark_engine", "mobsf_scanner"),
            "crypto" to listOf("crypto_hunter", "capa_floss"),
            "aes" to listOf("crypto_hunter"),
            "rsa" to listOf("crypto_hunter"),
            "network" to listOf("network_analyzer", "wireshark_analyzer"),
            "url" to listOf("network_analyzer"),
            "vuln" to listOf("vuln_scanner", "mobsf_scanner"),
            "exploit" to listOf("vuln_scanner", "coruna_ios"),
            "jni" to listOf("jni_analyzer"),
            "native" to listOf("jni_analyzer", "capstone_disasm"),
            "frida" to listOf("frida_generator"),
            "hook" to listOf("frida_generator", "objection_tool"),
            "disassembl" to listOf("capstone_disasm", "binary_ninja"),
            "arm" to listOf("capstone_disasm"),
            "x86" to listOf("capstone_disasm"),
            "binary" to listOf("elf_analyzer", "binary_ninja"),
            "reverse" to listOf("ida_mcp_bridge", "radare2_wrapper", "capstone_disasm"),
            "decompil" to listOf("jadx_decompiler", "dex_decompiler", "dnspy_decompiler"),
            "scan" to listOf("yara_scanner", "vuln_scanner", "quark_engine"),
            "analyze" to listOf("elf_analyzer", "network_analyzer"),
            "root" to listOf("coruna_ios"),
            "obfusc" to listOf("detect_it_easy"),
            "protocol" to listOf("network_analyzer"),
            "api" to listOf("network_analyzer", "objection_tool"),
            "permission" to listOf("apktool_engine"),
            // 30 Discord RE tools keywords
            "binary ninja" to listOf("binary_ninja"),
            "ghidra" to listOf("ghidra_analyzer", "ghidra_fox"),
            "cutter" to listOf("cutter_rizin"),
            "rizin" to listOf("cutter_rizin"),
            "jadx" to listOf("jadx_decompiler"),
            "dnspy" to listOf("dnspy_decompiler"),
            "dotnet" to listOf("dnspy_decompiler"),
            "x64dbg" to listOf("x64dbg_bridge"),
            "ollydbg" to listOf("ollydbg_debugger"),
            "immunity" to listOf("immunity_debugger"),
            "windbg" to listOf("windbg_analyzer"),
            "gdb" to listOf("gdb_lldb_bridge"),
            "lldb" to listOf("gdb_lldb_bridge"),
            "unicorn" to listOf("unicorn_emulator"),
            "unidbg" to listOf("unidbg_engine", "unicorn_emulator"),
            "qiling" to listOf("qiling_engine"),
            "dynamorio" to listOf("dynamorio_pin"),
            "pin" to listOf("dynamorio_pin"),
            "qbdi" to listOf("qbdi_tracer"),
            "angr" to listOf("angr_framework"),
            "symbolic" to listOf("angr_framework", "manticore_se", "triton_engine"),
            "manticore" to listOf("manticore_se"),
            "triton" to listOf("triton_engine"),
            "binsync" to listOf("binsync_collab"),
            "capa" to listOf("capa_floss"),
            "floss" to listOf("capa_floss"),
            "die" to listOf("detect_it_easy"),
            "packer" to listOf("detect_it_easy", "pe_bear"),
            "pe-bear" to listOf("pe_bear"),
            "bindiff" to listOf("bindiff_compare"),
            "apktool" to listOf("apktool_engine"),
            "smali" to listOf("apktool_engine"),
            "objection" to listOf("objection_tool"),
            "mobsf" to listOf("mobsf_scanner"),
            "quark" to listOf("quark_engine"),
            "wireshark" to listOf("wireshark_analyzer"),
            "pcap" to listOf("wireshark_analyzer"),
            "burp" to listOf("burp_zap_proxy"),
            "zap" to listOf("burp_zap_proxy"),
            "proxy" to listOf("burp_zap_proxy"),
            "imhex" to listOf("imhex_editor"),
            "hex editor" to listOf("imhex_editor"),
            "collaboration" to listOf("binsync_collab"),
            "team" to listOf("binsync_collab"),
            "emulation" to listOf("unicorn_emulator", "unidbg_engine", "qiling_engine"),
            "sandbox" to listOf("qiling_engine"),
            "debugger" to listOf("x64dbg_bridge", "gdb_lldb_bridge", "windbg_analyzer"),
            "windows" to listOf("x64dbg_bridge", "ollydbg_debugger"),
            "web" to listOf("burp_zap_proxy"),
            "packet" to listOf("wireshark_analyzer"),
            "traffic" to listOf("wireshark_analyzer"),
            // 7 additional Discord tools
            "fox" to listOf("ghidra_fox"),
            "federicodotta" to listOf("ghidra_fox"),
            "objc" to listOf("ida_objc_types"),
            "objective-c" to listOf("ida_objc_types"),
            "ios types" to listOf("ida_objc_types"),
            "poomsmart" to listOf("ida_objc_types"),
            "heresy" to listOf("heresy_react_native"),
            "react native" to listOf("heresy_react_native"),
            "rn" to listOf("heresy_react_native"),
            "pilfer" to listOf("heresy_react_native"),
            "edbg" to listOf("edbg_ebpf"),
            "ebpf debug" to listOf("edbg_ebpf"),
            "shinoLeah" to listOf("edbg_ebpf"),
            "bpfroid" to listOf("bpfroid_trace"),
            "ebpf trace" to listOf("bpfroid_trace"),
            "yanivagman" to listOf("bpfroid_trace"),
            "syscall" to listOf("bpfroid_trace", "edbg_ebpf"),
            "reflutter" to listOf("reflutter_ssl"),
            "flutter" to listOf("reflutter_ssl"),
            "ssl pinning" to listOf("reflutter_ssl"),
            "dart" to listOf("reflutter_ssl"),
            "coruna" to listOf("coruna_ios"),
            "ios exploit" to listOf("coruna_ios"),
            "jailbreak" to listOf("coruna_ios"),
            "exploit kit" to listOf("coruna_ios")
        )

        for ((keyword, plugins) in keywordMap) {
            if (lowerMsg.contains(keyword)) {
                for (plugin in plugins) {
                    try {
                        val output = pluginEngine.executePlugin(plugin, "{}")
                        results.add("[" + plugin + "]: " + output.take(500))
                    } catch (e: Exception) {
                        results.add("[" + plugin + "]: Error - " + e.message)
                    }
                }
            }
        }
        return results
    }

    /**
     * AI response generation
     */
    private fun generateResponse(userMsg: String, context: List<ChatMessage>, pluginResults: List<String>): String {
        val sb = StringBuilder()

        if (pluginResults.isNotEmpty()) {
            sb.append("**Auto-executed plugins:**\n\n")
            for (r in pluginResults) {
                sb.append("- ").append(r).append("\n")
            }
            sb.append("\n---\n\n")
        }

        sb.append(generateAiResponse(userMsg, context))
        return sb.toString()
    }

    /**
     * Simulated AI response
     */
    private fun generateAiResponse(userMsg: String, context: List<ChatMessage>): String {
        val lowerMsg = userMsg.lowercase(Locale.getDefault())

        if (lowerMsg.contains("help") || lowerMsg.contains("?") || lowerMsg.contains("list") || lowerMsg.contains("menu")) {
            return buildString {
                append("## Hermes Analyzer - AI Assistant\n\n")
                append("### Available Plugin Categories:\n\n")
                append("**Binary Analysis (14 plugins):**\n")
                append("- ELF Analyzer, DEX Decompiler, String Extractor, Capstone Disassembler\n")
                append("- Binary Ninja, Ghidra Analyzer, Cutter, JADX, dnSpy\n")
                append("- CAPA+FLOSS, Detect It Easy, PE-bear, BinDiff, APKTool\n\n")
                append("**Security (6 plugins):**\n")
                append("- YARA Scanner, Vuln Scanner, Crypto Hunter, Quark Engine, MobSF Scanner, Coruna iOS\n\n")
                append("**Android (11 plugins):**\n")
                append("- APK Deep Scan, DEX Decompiler, JNI Analyzer, Frida Generator\n")
                append("- JADX, APKTool, Objection, MobSF, heresy RN, reFlutter, IDAObjcTypes\n\n")
                append("**Network (4 plugins):**\n")
                append("- Network Analyzer, Wireshark, Burp/ZAP, BPFroid\n\n")
                append("**Debugger (6 plugins):**\n")
                append("- x64dbg, OllyDbg, Immunity, WinDbg, GDB/LLDB, eDBG\n\n")
                append("**Emulation (4 plugins):**\n")
                append("- Unicorn, Unidbg, Qiling, DynamoRIO\n\n")
                append("**Symbolic Execution (3 plugins):**\n")
                append("- angr, Manticore, Triton\n\n")
                append("**Utilities (4 plugins):**\n")
                append("- QBDI Tracer, BinSync Collaboration, ImHex Editor, Ghidra FOX\n\n")
                append("### 8 AI Platforms:\n")
                append("OpenAI, Kimi, Qwen, Gemini, Claude, DeepSeek, Ollama, Suprninja\n\n")
                append("Plugins trigger automatically based on keywords. Just describe your goal!")
            }
        }

        if (lowerMsg.contains("agent") || lowerMsg.contains("auto") || lowerMsg.contains("plan")) {
            return buildString {
                append("## Autonomous Agent Mode\n\n")
                append("I can create multi-step analysis plans and execute tools in parallel.\n\n")
                append("**Example commands:**\n")
                append("- \"Comprehensive security audit of this APK\"\n")
                append("- \"Reverse engineer the crypto and network logic\"\n")
                append("- \"Full malware analysis with string extraction\"\n\n")
                append("The agent will:\n")
                append("1. Analyze your goal\n")
                append("2. Select relevant tools\n")
                append("3. Execute in parallel groups\n")
                append("4. Evaluate results and add more tools if needed\n")
                append("5. Generate a comprehensive report\n\n")
                append("**API Keys saved for: ").append(getActiveAiPlatforms().joinToString(", ") ?: "None").append("**")
            }
        }

        // Check active AI platforms
        val activePlatforms = getActiveAiPlatforms()
        if (activePlatforms.isEmpty()) {
            return buildString {
                append("**No AI API keys configured.**\n\n")
                append("Please set up API keys for at least one platform:\n")
                append("- OpenAI (GPT-4)\n")
                append("- Kimi (Moonshot AI)\n")
                append("- Qwen (Alibaba)\n")
                append("- Gemini (Google)\n")
                append("- Claude (Anthropic)\n")
                append("- DeepSeek\n")
                append("- Ollama (local)\n")
                append("- Suprninja\n\n")
                append("Go to Settings > AI API Keys to configure.")
            }
        }

        return buildString {
            append("Hermes AI (using: ").append(activePlatforms.joinToString(", ") { getPlatformDisplayName(it) })
            append(")\n\n")
            append("**Context**: ").append(context.size).append(" messages in memory\n")
            append("**Active platforms**: ").append(activePlatforms.size).append("/8\n\n")
            append("Enter a file path and describe your analysis goal.\n")
            append("I'll auto-select tools and run them in parallel to achieve it!")
        }
    }

    fun getHistory(): List<ChatMessage> {
        return db.getRecentMessages(100)
    }

    fun clearHistory() {
        db.clearMessages()
    }

    // ============================================================
    // DATABASE
    // ============================================================

    class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "hermes_chat.db", null, 2) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT, " +
                "content TEXT, " +
                "timestamp INTEGER, " +
                "plugin_used TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE messages ADD COLUMN plugin_used TEXT")
            }
        }

        fun saveMessage(role: String, content: String, pluginUsed: String? = null) {
            val db = writableDatabase
            val values = android.content.ContentValues()
            values.put("role", role)
            values.put("content", content)
            values.put("timestamp", System.currentTimeMillis())
            values.put("plugin_used", pluginUsed)
            db.insert("messages", null, values)
        }

        fun getRecentMessages(limit: Int): List<ChatMessage> {
            val messages = mutableListOf<ChatMessage>()
            val db = readableDatabase
            val cursor = db.query(
                "messages", null, null, null, null, null,
                "timestamp DESC", limit.toString()
            )
            if (cursor.moveToFirst()) {
                do {
                    messages.add(ChatMessage(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        role = cursor.getString(cursor.getColumnIndexOrThrow("role")),
                        content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        pluginUsed = cursor.getString(cursor.getColumnIndexOrThrow("plugin_used"))
                    ))
                } while (cursor.moveToNext())
            }
            cursor.close()
            return messages.reversed()
        }

        fun clearMessages() {
            writableDatabase.delete("messages", null, null)
        }
    }
}
