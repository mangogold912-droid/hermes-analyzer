package com.hermes.analyzer.ai

import android.content.Context
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
 */
class AIChatEngine(context: Context) {

    private val db: ChatDatabase = ChatDatabase(context)
    private val pluginEngine: PluginEngine = PluginEngine(context)

    companion object {
        private const val TAG = "AIChatEngine"
        private const val MAX_CONTEXT = 20
    }

    data class ChatMessage(
        val id: Long = 0,
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val pluginUsed: String? = null
    )

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
    private fun autoExecutePlugins(userMessage: String): List<String> {
        val lower = userMessage.lowercase()
        val results = mutableListOf<String>()
        val executed = mutableSetOf<String>()

        val keywordMap = mapOf(
            // ==== EXISTING KEYWORDS (keep all) ====
            "elf" to listOf("elf_analyzer"),
            "dex" to listOf("dex_decompiler"),
            "apk" to listOf("apk_deep_scan", "dex_decompiler"),
            "string" to listOf("string_extractor"),
            "ida" to listOf("ida_mcp_bridge"),
            "radare" to listOf("radare2_wrapper"),
            "r2" to listOf("radare2_wrapper"),
            "yara" to listOf("yara_scanner"),
            "malware" to listOf("yara_scanner", "vuln_scanner"),
            "crypto" to listOf("crypto_hunter"),
            "aes" to listOf("crypto_hunter"),
            "network" to listOf("network_analyzer"),
            "url" to listOf("network_analyzer"),
            "vuln" to listOf("vuln_scanner"),
            "exploit" to listOf("vuln_scanner"),
            "jni" to listOf("jni_analyzer"),
            "native" to listOf("jni_analyzer"),
            "frida" to listOf("frida_generator"),
            "hook" to listOf("frida_generator"),
            "disassembl" to listOf("capstone_disasm"),
            "arm" to listOf("capstone_disasm"),
            "x86" to listOf("capstone_disasm"),
            "binary" to listOf("elf_analyzer", "string_extractor"),
            "reverse" to listOf("radare2_wrapper", "capstone_disasm"),
            "decompil" to listOf("dex_decompiler", "ida_mcp_bridge"),
            "scan" to listOf("yara_scanner", "vuln_scanner", "apk_deep_scan"),
            "analyze" to listOf("elf_analyzer", "string_extractor", "network_analyzer"),
            "root" to listOf("apk_deep_scan", "jni_analyzer"),
            "obfusc" to listOf("dex_decompiler", "string_extractor"),
            "protocol" to listOf("network_analyzer"),
            "api" to listOf("network_analyzer", "frida_generator"),
            "permission" to listOf("apk_deep_scan"),

            // ==== 30 NEW KEYWORD MAPPINGS ====
            "binary ninja" to listOf("binary_ninja"),
            "ghidra" to listOf("ghidra_analyzer"),
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

            // ==== 7 ADDITIONAL KEYWORD MAPPINGS ====
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

        for ((keyword, pluginIds) in keywordMap) {
            if (lower.contains(keyword)) {
                for (pluginId in pluginIds) {
                    if (pluginId !in executed) {
                        executed.add(pluginId)
                        val result = pluginEngine.executePlugin(pluginId, mapOf("file" to "auto", "query" to userMessage))
                        results.add("[" + pluginId + "] " + result)
                        Log.i(TAG, "Auto-executed plugin: " + pluginId)
                    }
                }
            }
        }

        return results
    }

    /**
     * AI response generation
     */
    private fun generateResponse(
        userMessage: String,
        context: List<ChatMessage>,
        pluginResults: List<String>
    ): String {
        val sb = StringBuilder()

        // Show plugin execution results first if present
        if (pluginResults.isNotEmpty()) {
            sb.append("**Analysis Results:**\n\n")
            for (result in pluginResults) {
                sb.append("```\n").append(result).append("\n```\n\n")
            }
        }

        // Context-based response
        val lower = userMessage.lowercase()

        // Greeting
        if (lower.contains("hello") || lower.contains("hi ") || lower.contains("hey")) {
            sb.append("Hello! I'm **Hermes AI**, your advanced reverse engineering assistant. I can:\n\n")
            sb.append("- Analyze binaries (ELF, PE, DEX, APK)\n")
            sb.append("- Decompile and disassemble code\n")
            sb.append("- Find vulnerabilities and crypto\n")
            sb.append("- Generate Frida scripts\n")
            sb.append("- Scan with YARA rules\n")
            sb.append("- And much more...\n\n")
            sb.append("Just tell me what file you want to analyze or what you need help with!")
            return sb.toString()
        }

        // File analysis request
        if (lower.contains("analyze") || lower.contains("file") || lower.contains("binary")) {
            sb.append("I'll analyze the target file systematically. Here's my approach:\n\n")
            sb.append("1. **File Type Detection** - Identify format (ELF/PE/DEX/APK)\n")
            sb.append("2. **String Analysis** - Extract interesting strings\n")
            sb.append("3. **Structure Analysis** - Parse headers and sections\n")
            sb.append("4. **Security Scan** - Check for vulnerabilities\n")
            sb.append("5. **Report Generation** - Summarize findings\n\n")
            if (pluginResults.isNotEmpty()) {
                sb.append("I've already run the initial analysis above. Would you like me to go deeper into any specific area?")
            } else {
                sb.append("Please provide the file path or select a file to begin analysis.")
            }
            return sb.toString()
        }

        // Help request
        if (lower.contains("help") || lower.contains("what can you do")) {
            sb.append("## Available Capabilities\n\n")

            // Binary Analysis (13 plugins)
            sb.append("**Binary Analysis (13):**\n")
            sb.append("- ELF Analyzer: Headers, sections, symbols, relocations\n")
            sb.append("- Capstone Disasm: ARM/x86 disassembly engine\n")
            sb.append("- Binary Ninja: Advanced binary analysis platform\n")
            sb.append("- Ghidra Analyzer: NSA reverse engineering suite\n")
            sb.append("- Ghidra Fox: iOS/macOS Ghidra scripts (Federico Dotta)\n")
            sb.append("- Cutter/Rizin: Open-source reverse engineering GUI\n")
            sb.append("- dnSpy Decompiler: .NET assembly decompiler\n")
            sb.append("- Detect It Easy: Packer/compiler identifier\n")
            sb.append("- PE Bear: Portable Executable file analyzer\n")
            sb.append("- BinDiff Compare: Binary diffing and comparison\n")
            sb.append("- ImHex Editor: Hex pattern visualization editor\n")
            sb.append("- IDA MCP Bridge: IDA Pro integration bridge\n")
            sb.append("- Radare2 Wrapper: Command-line reverse framework\n\n")

            // Security (6 plugins)
            sb.append("**Security (6):**\n")
            sb.append("- YARA Scanner: Malware pattern detection\n")
            sb.append("- Vuln Scanner: CVE, overflow, injection checks\n")
            sb.append("- Crypto Hunter: Find AES, RSA, hash algorithms\n")
            sb.append("- CAPA/FLOSS: Capability extraction and string deobfuscation\n")
            sb.append("- Quark Engine: Android malware scoring\n")
            sb.append("- Coruna iOS: iOS exploitation and jailbreak toolkit\n\n")

            // Mobile / Android (11 plugins)
            sb.append("**Mobile / Android (11):**\n")
            sb.append("- APK Deep Scan: Permissions, components, native libs\n")
            sb.append("- DEX Decompiler: Dalvik bytecode decompilation\n")
            sb.append("- JNI Analyzer: Native method call analysis\n")
            sb.append("- Frida Generator: Dynamic instrumentation scripts\n")
            sb.append("- JADX Decompiler: Android Java decompiler\n")
            sb.append("- Apktool Engine: APK reverse engineering\n")
            sb.append("- Objection Tool: Runtime mobile exploration\n")
            sb.append("- MobSF Scanner: Mobile security framework\n")
            sb.append("- Heresy React Native: React Native analysis and deobfuscation\n")
            sb.append("- ReFlutter SSL: Flutter SSL pinning bypass tool\n")
            sb.append("- IDA ObjC Types: Objective-C/iOS type parser for IDA (PoomSmart)\n\n")

            // Network (4 plugins)
            sb.append("**Network (4):**\n")
            sb.append("- Network Analyzer: URLs, protocols, SSL pinning\n")
            sb.append("- Wireshark Analyzer: PCAP and traffic analysis\n")
            sb.append("- Burp/ZAP Proxy: Web interception proxy\n")
            sb.append("- BPFroid Trace: eBPF Android syscall tracer (yanivagman)\n\n")

            // Debuggers (6 plugins)
            sb.append("**Debuggers (6):**\n")
            sb.append("- x64dbg Bridge: Windows user-mode debugger\n")
            sb.append("- OllyDbg Debugger: Legacy 32-bit debugger\n")
            sb.append("- Immunity Debugger: Exploit development debugger\n")
            sb.append("- WinDbg Analyzer: Windows kernel debugger\n")
            sb.append("- GDB/LLDB Bridge: GNU and LLVM debugger bridge\n")
            sb.append("- eDBG eBPF: eBPF-based kernel debugger (shinoLeah)\n\n")

            // Emulation (4 plugins)
            sb.append("**Emulation (4):**\n")
            sb.append("- Unicorn Emulator: CPU emulation framework\n")
            sb.append("- Unidbg Engine: Android native emulation\n")
            sb.append("- Qiling Engine: Advanced binary emulation\n")
            sb.append("- DynamoRIO/Pin: Dynamic binary instrumentation\n\n")

            // Symbolic Execution (3 plugins)
            sb.append("**Symbolic Execution (3):**\n")
            sb.append("- Angr Framework: Python binary analysis platform\n")
            sb.append("- Manticore SE: Symbolic execution tool\n")
            sb.append("- Triton Engine: Dynamic binary analysis\n\n")

            // Utilities (3 plugins)
            sb.append("**Utilities (3):**\n")
            sb.append("- QBDI Tracer: Dynamic binary instrumentation tracer\n")
            sb.append("- BinSync Collab: Reverse engineering collaboration\n")
            sb.append("- String Extractor: All strings with categorization\n\n")

            sb.append("Just type what you want to analyze!")
            return sb.toString()
        }

        // When plugin results are present
        if (pluginResults.isNotEmpty()) {
            sb.append("Based on the automated analysis above, I've identified several areas of interest. ")
            sb.append("The analysis used ").append(pluginResults.size).append(" plugin(s) based on your query keywords.\n\n")
            sb.append("Would you like me to:\n")
            sb.append("- **Deep dive** into specific findings?\n")
            sb.append("- **Explain** what the results mean?\n")
            sb.append("- **Suggest** next steps or tools?\n")
            sb.append("- **Generate** exploit/script code?")
            return sb.toString()
        }

        // Default response
        sb.append("I'm analyzing your request...\n\n")
        sb.append("To provide the best assistance, could you clarify:\n")
        sb.append("- What **type of file** are you analyzing? (ELF, APK, DEX, etc.)\n")
        sb.append("- What **specific information** do you need?\n")
        sb.append("- Are you looking for **vulnerabilities**, **structure**, or **behavior**?\n\n")
        sb.append("Or type **'help'** to see all available capabilities.")

        return sb.toString()
    }

    /**
     * Get history
     */
    fun getHistory(limit: Int = 100): List<ChatMessage> = db.getRecentMessages(limit)

    /**
     * Clear history
     */
    fun clearHistory() = db.clearAll()

    /**
     * SQLite database
     */
    class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "hermes_chat.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp INTEGER DEFAULT 0,
                    plugin_used TEXT
                )
            """)
            // Create index
            db.execSQL("CREATE INDEX idx_timestamp ON messages(timestamp)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

        fun saveMessage(role: String, content: String, pluginUsed: String? = null) {
            val db = writableDatabase
            db.execSQL(
                "INSERT INTO messages (role, content, timestamp, plugin_used) VALUES (?, ?, ?, ?)",
                arrayOf(role, content, System.currentTimeMillis(), pluginUsed)
            )
        }

        fun getRecentMessages(limit: Int): List<ChatMessage> {
            val list = mutableListOf<ChatMessage>()
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, role, content, timestamp, plugin_used FROM messages ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            while (cursor.moveToNext()) {
                list.add(ChatMessage(
                    id = cursor.getLong(0),
                    role = cursor.getString(1),
                    content = cursor.getString(2),
                    timestamp = cursor.getLong(3),
                    pluginUsed = cursor.getString(4)
                ))
            }
            cursor.close()
            return list.reversed()
        }

        fun clearAll() {
            writableDatabase.execSQL("DELETE FROM messages")
        }
    }
}
