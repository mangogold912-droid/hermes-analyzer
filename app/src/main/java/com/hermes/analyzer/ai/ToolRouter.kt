package com.hermes.analyzer.ai

import android.content.Context
import com.hermes.analyzer.sandbox.SandboxManager
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
)

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class ToolCall(
    val toolName: String,
    val arguments: Map<String, String>
)

data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val output: String,
    val error: String? = null
)

class ToolRouter(private val context: Context) {

    companion object {
        private const val DEFAULT_SANDBOX_ID = "sandbox-binary"
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                name = "analyze_file",
                description = "Run file analysis (strings, xxd, file) on a given file path",
                parameters = listOf(
                    ToolParameter(
                        name = "filePath",
                        type = "string",
                        description = "Absolute path to the file to analyze"
                    )
                )
            ),
            ToolDefinition(
                name = "run_shell",
                description = "Execute a shell command in sandbox",
                parameters = listOf(
                    ToolParameter(
                        name = "command",
                        type = "string",
                        description = "Shell command to execute"
                    )
                )
            ),
            ToolDefinition(
                name = "search_strings",
                description = "Search for a pattern in file strings",
                parameters = listOf(
                    ToolParameter(
                        name = "filePath",
                        type = "string",
                        description = "Absolute path to the file"
                    ),
                    ToolParameter(
                        name = "pattern",
                        type = "string",
                        description = "Pattern to search for"
                    )
                )
            ),
            ToolDefinition(
                name = "extract_urls",
                description = "Extract all URLs/IPs from file",
                parameters = listOf(
                    ToolParameter(
                        name = "filePath",
                        type = "string",
                        description = "Absolute path to the file"
                    )
                )
            ),
            ToolDefinition(
                name = "hex_dump",
                description = "Hex dump at offset",
                parameters = listOf(
                    ToolParameter(
                        name = "filePath",
                        type = "string",
                        description = "Absolute path to the file"
                    ),
                    ToolParameter(
                        name = "offset",
                        type = "number",
                        description = "Byte offset to start dumping from",
                        required = false
                    ),
                    ToolParameter(
                        name = "length",
                        type = "number",
                        description = "Number of bytes to dump",
                        required = false
                    )
                )
            ),
            ToolDefinition(
                name = "decompile_apk",
                description = "APK decompilation steps",
                parameters = listOf(
                    ToolParameter(
                        name = "apkPath",
                        type = "string",
                        description = "Absolute path to the APK file"
                    ),
                    ToolParameter(
                        name = "outputDir",
                        type = "string",
                        description = "Directory to store decompiled output",
                        required = false
                    )
                )
            ),
            ToolDefinition(
                name = "security_scan",
                description = "Security vulnerability scan",
                parameters = listOf(
                    ToolParameter(
                        name = "targetPath",
                        type = "string",
                        description = "Path to target file or directory to scan"
                    ),
                    ToolParameter(
                        name = "scanType",
                        type = "string",
                        description = "Type of scan: basic, deep, manifest",
                        required = false
                    )
                )
            ),
            ToolDefinition(
                name = "read_file",
                description = "Read a file's content",
                parameters = listOf(
                    ToolParameter(
                        name = "filePath",
                        type = "string",
                        description = "Absolute path to the file to read"
                    ),
                    ToolParameter(
                        name = "maxLines",
                        type = "number",
                        description = "Maximum lines to read",
                        required = false
                    )
                )
            )
        )
    }

    fun parseToolCalls(llmOutput: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()
        val regex = Regex("""<tool_call>\s*name:\s*(\S+)\s*arguments:\s*([\s\S]*?)</tool_call>""")
        val matches = regex.findAll(llmOutput)

        for (match in matches) {
            val toolName = match.groupValues[1].trim()
            val argsBlock = match.groupValues[2].trim()
            val arguments = parseArgumentsBlock(argsBlock)
            toolCalls.add(ToolCall(toolName = toolName, arguments = arguments))
        }

        return toolCalls
    }

    private fun parseArgumentsBlock(block: String): Map<String, String> {
        val args = mutableMapOf<String, String>()
        val lines = block.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val colonIndex = trimmed.indexOf(':')
            if (colonIndex > 0) {
                val key = trimmed.substring(0, colonIndex).trim()
                var value = trimmed.substring(colonIndex + 1).trim()
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length - 1)
                }
                args[key] = value
            }
        }
        return args
    }

    fun executeTool(call: ToolCall): ToolResult {
        return when (call.toolName) {
            "analyze_file" -> executeAnalyzeFile(call.arguments)
            "run_shell" -> executeRunShell(call.arguments)
            "search_strings" -> executeSearchStrings(call.arguments)
            "extract_urls" -> executeExtractUrls(call.arguments)
            "hex_dump" -> executeHexDump(call.arguments)
            "decompile_apk" -> executeDecompileApk(call.arguments)
            "security_scan" -> executeSecurityScan(call.arguments)
            "read_file" -> executeReadFile(call.arguments)
            else -> ToolResult(
                toolName = call.toolName,
                success = false,
                output = "",
                error = "Unknown tool: ${call.toolName}"
            )
        }
    }

    private fun executeAnalyzeFile(args: Map<String, String>): ToolResult {
        val filePath = args["filePath"] ?: return ToolResult(
            toolName = "analyze_file",
            success = false,
            output = "",
            error = "Missing required parameter: filePath"
        )

        val file = File(filePath)
        if (!file.exists()) {
            return ToolResult(
                toolName = "analyze_file",
                success = false,
                output = "",
                error = "File not found: $filePath"
            )
        }

        val sb = StringBuilder()
        sb.append("=== File Analysis: ").append(filePath).append(" ===\n")

        sb.append("\n--- file command ---\n")
        try {
            val result = runShellCommand("file \"$filePath\"")
            sb.append(result)
        } catch (e: Exception) {
            sb.append("Error running file command: ").append(e.message).append("\n")
        }

        sb.append("\n--- strings (first 50) ---\n")
        try {
            val result = runShellCommand("strings \"$filePath\" | head -50")
            sb.append(result)
        } catch (e: Exception) {
            sb.append("Error running strings: ").append(e.message).append("\n")
        }

        sb.append("\n--- xxd (first 256 bytes) ---\n")
        try {
            val result = runShellCommand("xxd -l 256 \"$filePath\"")
            sb.append(result)
        } catch (e: Exception) {
            sb.append("Error running xxd: ").append(e.message).append("\n")
        }

        return ToolResult(
            toolName = "analyze_file",
            success = true,
            output = sb.toString()
        )
    }

    private fun executeRunShell(args: Map<String, String>): ToolResult {
        val command = args["command"] ?: return ToolResult(
            toolName = "run_shell",
            success = false,
            output = "",
            error = "Missing required parameter: command"
        )

        return try {
            val sandboxManager = SandboxManager(context)
            val result = sandboxManager.execute(DEFAULT_SANDBOX_ID, command, timeoutSec = 60)
            ToolResult(
                toolName = "run_shell",
                success = result.exitCode == 0,
                output = result.output,
                error = if (result.exitCode != 0) "Exit code: ${result.exitCode}\n${result.error}" else null
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = "run_shell",
                success = false,
                output = "",
                error = "Shell execution failed: ${e.message}"
            )
        }
    }

    private fun executeSearchStrings(args: Map<String, String>): ToolResult {
        val filePath = args["filePath"] ?: return ToolResult(
            toolName = "search_strings",
            success = false,
            output = "",
            error = "Missing required parameter: filePath"
        )
        val pattern = args["pattern"] ?: return ToolResult(
            toolName = "search_strings",
            success = false,
            output = "",
            error = "Missing required parameter: pattern"
        )

        return try {
            val result = runShellCommand(
                "strings \"$filePath\" | grep -i \"$pattern\" | head -50"
            )
            ToolResult(
                toolName = "search_strings",
                success = true,
                output = result
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = "search_strings",
                success = false,
                output = "",
                error = "Search failed: ${e.message}"
            )
        }
    }

    private fun executeExtractUrls(args: Map<String, String>): ToolResult {
        val filePath = args["filePath"] ?: return ToolResult(
            toolName = "extract_urls",
            success = false,
            output = "",
            error = "Missing required parameter: filePath"
        )

        val file = File(filePath)
        if (!file.exists()) {
            return ToolResult(
                toolName = "extract_urls",
                success = false,
                output = "",
                error = "File not found: $filePath"
            )
        }

        return try {
            val urlRegex = Regex(
                """https?://[a-zA-Z0-9./?=_-]+|www\.[a-zA-Z0-9./?=_-]+|\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b"""
            )
            val sb = StringBuilder()

            val content = file.readText()
            val contentUrls = urlRegex.findAll(content).map { it.value }.distinct().toList()

            if (contentUrls.isNotEmpty()) {
                sb.append("=== URLs/IPs found in file content ===\n")
                for (url in contentUrls) {
                    sb.append(url).append("\n")
                }
            }

            val stringsOutput = runShellCommand("strings \"$filePath\"")
            val stringUrls = urlRegex.findAll(stringsOutput).map { it.value }.distinct().toList()
            if (stringUrls.isNotEmpty()) {
                sb.append("\n=== URLs/IPs found in strings ===\n")
                for (url in stringUrls) {
                    sb.append(url).append("\n")
                }
            }

            if (sb.isEmpty()) {
                sb.append("No URLs or IPs found.\n")
            }

            ToolResult(
                toolName = "extract_urls",
                success = true,
                output = sb.toString()
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = "extract_urls",
                success = false,
                output = "",
                error = "URL extraction failed: ${e.message}"
            )
        }
    }

    private fun executeHexDump(args: Map<String, String>): ToolResult {
        val filePath = args["filePath"] ?: return ToolResult(
            toolName = "hex_dump",
            success = false,
            output = "",
            error = "Missing required parameter: filePath"
        )
        val offset = args["offset"] ?: "0"
        val length = args["length"] ?: "256"

        return try {
            val result = runShellCommand("xxd -s $offset -l $length \"$filePath\"")
            ToolResult(
                toolName = "hex_dump",
                success = true,
                output = result
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = "hex_dump",
                success = false,
                output = "",
                error = "Hex dump failed: ${e.message}"
            )
        }
    }

    private fun executeDecompileApk(args: Map<String, String>): ToolResult {
        val apkPath = args["apkPath"] ?: return ToolResult(
            toolName = "decompile_apk",
            success = false,
            output = "",
            error = "Missing required parameter: apkPath"
        )
        val outputDir = args["outputDir"] ?: "${apkPath}_decompiled"

        val sb = StringBuilder()
        sb.append("=== APK Decompilation: ").append(apkPath).append(" ===\n")
        sb.append("Output directory: ").append(outputDir).append("\n\n")

        sb.append("--- Step 1: apktool d ---\n")
        try {
            val result = runShellCommand("apktool d \"$apkPath\" -o \"$outputDir/apktool\" -f")
            sb.append(result)
        } catch (e: Exception) {
            sb.append("apktool failed: ").append(e.message).append("\n")
        }

        sb.append("\n--- Step 2: unzip listing ---\n")
        try {
            val result = runShellCommand("unzip -l \"$apkPath\"")
            sb.append(result)
        } catch (e: Exception) {
            sb.append("unzip listing failed: ").append(e.message).append("\n")
        }

        sb.append("\n--- Step 3: dex2jar ---\n")
        try {
            val result = runShellCommand(
                "d2j-dex2jar \"$apkPath\" -o \"$outputDir/classes.jar\" --force"
            )
            sb.append(result)
        } catch (e: Exception) {
            sb.append("dex2jar failed: ").append(e.message).append("\n")
        }

        return ToolResult(
            toolName = "decompile_apk",
            success = true,
            output = sb.toString()
        )
    }

    private fun executeSecurityScan(args: Map<String, String>): ToolResult {
        val targetPath = args["targetPath"] ?: return ToolResult(
            toolName = "security_scan",
            success = false,
            output = "",
            error = "Missing required parameter: targetPath"
        )
        val scanType = args["scanType"] ?: "basic"

        val file = File(targetPath)
        if (!file.exists()) {
            return ToolResult(
                toolName = "security_scan",
                success = false,
                output = "",
                error = "Target not found: $targetPath"
            )
        }

        val sb = StringBuilder()
        sb.append("=== Security Scan: ").append(targetPath)
            .append(" (type: ").append(scanType).append(") ===\n")

        sb.append("\n--- Permissions ---\n")
        sb.append("Readable: ").append(file.canRead())
            .append(", Writable: ").append(file.canWrite())
            .append(", Executable: ").append(file.canExecute()).append("\n")

        sb.append("\n--- Secret Scan ---\n")
        try {
            val secretsResult = runShellCommand(
                "strings \"$targetPath\" | grep -iE \"password|secret|api_key|token|private_key|admin\" | head -30"
            )
            sb.append(secretsResult.ifEmpty { "No obvious secrets found.\n" })
        } catch (e: Exception) {
            sb.append("Secret scan error: ").append(e.message).append("\n")
        }

        sb.append("\n--- Network Indicators ---\n")
        try {
            val netResult = runShellCommand(
                "strings \"$targetPath\" | grep -iE \"http://|https://|tcp://|udp://|socket\" | head -20"
            )
            sb.append(netResult.ifEmpty { "No obvious network indicators.\n" })
        } catch (e: Exception) {
            sb.append("Network scan error: ").append(e.message).append("\n")
        }

        if (scanType == "deep") {
            sb.append("\n--- Deep Scan: Entropy Check ---\n")
            try {
                val entropyResult = runShellCommand(
                    "ent \"$targetPath\" 2>/dev/null || echo 'ent not available'"
                )
                sb.append(entropyResult)
            } catch (e: Exception) {
                sb.append("Entropy check failed: ").append(e.message).append("\n")
            }
        }

        if (scanType == "manifest" && (targetPath.endsWith(".apk") || targetPath.endsWith(".zip"))) {
            sb.append("\n--- Manifest Scan ---\n")
            try {
                val manifestResult = runShellCommand(
                    "unzip -p \"$targetPath\" AndroidManifest.xml 2>/dev/null | strings | head -50"
                )
                sb.append(manifestResult)
            } catch (e: Exception) {
                sb.append("Manifest scan failed: ").append(e.message).append("\n")
            }
        }

        return ToolResult(
            toolName = "security_scan",
            success = true,
            output = sb.toString()
        )
    }

    private fun executeReadFile(args: Map<String, String>): ToolResult {
        val filePath = args["filePath"] ?: return ToolResult(
            toolName = "read_file",
            success = false,
            output = "",
            error = "Missing required parameter: filePath"
        )
        val maxLinesStr = args["maxLines"]
        val maxLines = maxLinesStr?.toIntOrNull() ?: Int.MAX_VALUE

        val file = File(filePath)
        if (!file.exists()) {
            return ToolResult(
                toolName = "read_file",
                success = false,
                output = "",
                error = "File not found: $filePath"
            )
        }

        return try {
            val sb = StringBuilder()
            var lineCount = 0
            file.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (lineCount < maxLines) {
                        sb.append(line).append("\n")
                        lineCount++
                    }
                }
            }
            if (lineCount >= maxLines && maxLines != Int.MAX_VALUE) {
                sb.append("... (truncated after ").append(maxLines).append(" lines)\n")
            }
            ToolResult(
                toolName = "read_file",
                success = true,
                output = sb.toString()
            )
        } catch (e: Exception) {
            ToolResult(
                toolName = "read_file",
                success = false,
                output = "",
                error = "Failed to read file: ${e.message}"
            )
        }
    }

    private fun runShellCommand(command: String): String {
        val process = Runtime.getRuntime().exec(command)
        val stdout = BufferedReader(InputStreamReader(process.inputStream))
        val stderr = BufferedReader(InputStreamReader(process.errorStream))

        val output = StringBuilder()
        stdout.use { reader ->
            reader.forEachLine { line ->
                output.append(line).append("\n")
            }
        }
        stderr.use { reader ->
            reader.forEachLine { line ->
                output.append("STDERR: ").append(line).append("\n")
            }
        }

        process.waitFor()
        return output.toString()
    }

    fun formatToolResults(results: List<ToolResult>): String {
        val sb = StringBuilder()
        sb.append("=== Tool Execution Results ===\n")
        for (result in results) {
            sb.append("\n--- Tool: ").append(result.toolName).append(" ---\n")
            sb.append("Success: ").append(result.success).append("\n")
            if (result.output.isNotEmpty()) {
                sb.append("Output:\n").append(result.output).append("\n")
            }
            if (result.error != null) {
                sb.append("Error: ").append(result.error).append("\n")
            }
        }
        sb.append("\n=== End of Results ===\n")
        return sb.toString()
    }

    fun getSystemPromptWithTools(): String {
        val sb = StringBuilder()
        sb.append("You are an AI assistant with access to the following tools. ")
        sb.append("Use them when needed to help analyze files, run commands, and investigate security concerns.\n")
        sb.append("\n")
        sb.append("Available Tools:\n")

        val definitions = getToolDefinitions()
        for (tool in definitions) {
            sb.append("\n")
            sb.append("## ").append(tool.name).append("\n")
            sb.append("Description: ").append(tool.description).append("\n")
            sb.append("Parameters:\n")
            for (param in tool.parameters) {
                val reqStr = if (param.required) " (required)" else " (optional)"
                sb.append("  - ").append(param.name)
                    .append(" (").append(param.type).append(")")
                    .append(reqStr).append(": ")
                    .append(param.description).append("\n")
            }
        }

        sb.append("\n")
        sb.append("To call a tool, use the following exact format:\n")
        sb.append("\n")
        sb.append("<tool_call>\n")
        sb.append("name: <tool_name>\n")
        sb.append("arguments:\n")
        sb.append("  <param1>: \"<value1>\"\n")
        sb.append("  <param2>: \"<value2>\"\n")
        sb.append("</tool_call>\n")
        sb.append("\n")
        sb.append("You can make multiple tool calls in one response. ")
        sb.append("Each tool call must be wrapped in <tool_call>...</tool_call> tags.\n")
        sb.append("After tool execution, you will receive the results and should analyze them ")
        sb.append("to provide insights or decide on next steps.\n")
        sb.append("Always wait for tool results before drawing conclusions about file contents.\n")

        return sb.toString()
    }
}
