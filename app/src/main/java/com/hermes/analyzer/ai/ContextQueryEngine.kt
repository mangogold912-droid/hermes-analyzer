package com.hermes.analyzer.ai

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Context Query Engine inspired by Claude Code's QueryEngine.
 *
 * Indexes and searches file contents to provide relevant context to an LLM agent.
 * Supports both exact and fuzzy search across binary and text files.
 */
data class ContextResult(
    val filePath: String,
    val relevanceScore: Float,
    val snippet: String,
    val lineNumber: Int? = null,
    val matchType: String // exact, fuzzy, regex, string_ref
)

data class FileIndex(
    val path: String,
    val size: Long,
    val type: String,
    val strings: List<String>,
    val imports: List<String>,
    val functions: List<String>,
    val classes: List<String>
)

class ContextQueryEngine(private val context: android.content.Context) {

    companion object {
        private const val TAG = "ContextQueryEngine"
        private val FUN_REGEX = Regex("""\bfun\s+[a-zA-Z_]""")
        private val CLASS_REGEX = Regex("""\b(class|interface|object|enum|data\s+class|sealed\s+class|abstract\s+class|annotation\s+class)\s+""")
        private val IMPORT_REGEX = Regex("""^\s*(import|using|from|include|require)\b""")
    }

    private val index = mutableMapOf<String, FileIndex>()

    /**
     * Clear all indexed data.
     */
    fun clearIndex() {
        index.clear()
    }

    /**
     * Index a single file and return its extracted metadata.
     */
    fun indexFile(filePath: String): FileIndex {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $filePath")
        }

        val type = determineFileType(filePath, file)
        val allLines = if (type != "binary") {
            try {
                file.readLines()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read lines from $filePath", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        val strings = if (type == "binary") {
            extractBinaryStrings(file)
        } else {
            allLines.filter { it.isNotBlank() }.distinct().take(500)
        }

        val imports = if (type == "code") extractImports(allLines) else emptyList()
        val functions = if (type == "code") extractFunctions(allLines) else emptyList()
        val classes = if (type == "code") extractClasses(allLines) else emptyList()

        val fileIndex = FileIndex(
            path = filePath,
            size = file.length(),
            type = type,
            strings = strings,
            imports = imports,
            functions = functions,
            classes = classes
        )

        index[filePath] = fileIndex
        Log.d(TAG, "Indexed $filePath ($type): ${strings.size} strings, ${imports.size} imports, ${functions.size} functions, ${classes.size} classes")
        return fileIndex
    }

    /**
     * Search for relevant context across all indexed files, or a single file.
     */
    fun query(query: String, filePath: String? = null): List<ContextResult> {
        val results = mutableListOf<ContextResult>()

        val targets = if (filePath != null) {
            val fi = index[filePath] ?: if (File(filePath).exists()) indexFile(filePath) else null
            listOfNotNull(fi)
        } else {
            index.values.toList()
        }

        for (fileIndex in targets) {
            results.addAll(searchInIndex(fileIndex, query))
        }

        return results.sortedByDescending { it.relevanceScore }
    }

    /**
     * Regex pattern search in a specific file.
     */
    fun searchPattern(pattern: String, filePath: String): List<ContextResult> {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $filePath")
        }

        val regex = try {
            pattern.toRegex()
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid regex pattern: $pattern")
        }

        return try {
            file.readLines().mapIndexedNotNull { lineNum, line ->
                val matches = regex.findAll(line).toList()
                if (matches.isNotEmpty()) {
                    ContextResult(
                        filePath = filePath,
                        relevanceScore = 1.0f,
                        snippet = line.trim(),
                        lineNumber = lineNum + 1,
                        matchType = "regex"
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search pattern in $filePath", e)
            emptyList()
        }
    }

    /**
     * Find strings related to a keyword in a specific file.
     */
    fun getRelatedStrings(keyword: String, filePath: String): List<String> {
        val fileIndex = index[filePath] ?: if (File(filePath).exists()) indexFile(filePath) else return emptyList()
        val lowerKeyword = keyword.lowercase()
        return fileIndex.strings.filter { str ->
            str.lowercase().contains(lowerKeyword)
        }
    }

    /**
     * Generate a markdown overview of the file.
     */
    fun getFileOverview(filePath: String): String {
        val fileIndex = index[filePath] ?: if (File(filePath).exists()) indexFile(filePath) else {
            return "## File Overview: $filePath\n\nFile not found or could not be indexed.\n"
        }

        val sb = StringBuilder()

        sb.append("## File Overview: ").append(fileIndex.path).append("\n")
        sb.append("\n")
        sb.append("- **Size**: ").append(fileIndex.size).append(" bytes\n")
        sb.append("- **Type**: ").append(fileIndex.type).append("\n")
        sb.append("- **Imports**: ").append(fileIndex.imports.size).append("\n")
        sb.append("- **Functions**: ").append(fileIndex.functions.size).append("\n")
        sb.append("- **Classes**: ").append(fileIndex.classes.size).append("\n")
        sb.append("\n")

        if (fileIndex.imports.isNotEmpty()) {
            sb.append("### Imports\n")
            fileIndex.imports.forEach { imp ->
                sb.append("- ").append(imp).append("\n")
            }
            sb.append("\n")
        }

        if (fileIndex.classes.isNotEmpty()) {
            sb.append("### Classes\n")
            fileIndex.classes.forEach { cls ->
                sb.append("- ").append(cls).append("\n")
            }
            sb.append("\n")
        }

        if (fileIndex.functions.isNotEmpty()) {
            sb.append("### Functions\n")
            fileIndex.functions.forEach { func ->
                sb.append("- ").append(func).append("\n")
            }
            sb.append("\n")
        }

        if (fileIndex.strings.isNotEmpty() && fileIndex.type == "binary") {
            sb.append("### Strings (first 20)\n")
            fileIndex.strings.take(20).forEach { str ->
                sb.append("- ").append(str).append("\n")
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun determineFileType(filePath: String, file: File): String {
        return when {
            isBinaryFile(file) -> "binary"
            filePath.endsWith(".kt") || filePath.endsWith(".kts") || filePath.endsWith(".java") -> "code"
            filePath.endsWith(".xml") -> "xml"
            filePath.endsWith(".gradle") || filePath.endsWith(".gradle.kts") -> "gradle"
            filePath.endsWith(".json") -> "json"
            filePath.endsWith(".md") || filePath.endsWith(".markdown") -> "markdown"
            filePath.endsWith(".py") -> "code"
            filePath.endsWith(".js") || filePath.endsWith(".ts") || filePath.endsWith(".jsx") || filePath.endsWith(".tsx") -> "code"
            filePath.endsWith(".swift") || filePath.endsWith(".m") || filePath.endsWith(".mm") -> "code"
            filePath.endsWith(".go") || filePath.endsWith(".rs") || filePath.endsWith(".cpp") || filePath.endsWith(".c") || filePath.endsWith(".h") -> "code"
            else -> "text"
        }
    }

    private fun isBinaryFile(file: File): Boolean {
        try {
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(1024)
                val bytesRead = stream.read(buffer)
                if (bytesRead > 0) {
                    for (i in 0 until bytesRead) {
                        if (buffer[i].toInt() == 0) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking binary file: ${file.absolutePath}", e)
        }
        return false
    }

    private fun extractBinaryStrings(file: File): List<String> {
        // Try the `strings` command if available (common on Unix-like systems)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("strings", "-n", "4", file.absolutePath))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()
            if (lines.isNotEmpty()) {
                return lines.filter { it.length >= 4 }.distinct().take(500)
            }
        } catch (e: Exception) {
            Log.w(TAG, "strings command failed for ${file.absolutePath}, using fallback", e)
        }

        // Fallback: read bytes directly and extract printable sequences
        return try {
            val content = file.readBytes()
            extractPrintableStrings(content, 4)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract strings from ${file.absolutePath}", e)
            emptyList()
        }
    }

    private fun extractPrintableStrings(bytes: ByteArray, minLength: Int): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126 || c in 160..255) {
                sb.append(c.toChar())
            } else {
                if (sb.length >= minLength) {
                    result.add(sb.toString())
                }
                sb.clear()
            }
        }
        if (sb.length >= minLength) {
            result.add(sb.toString())
        }
        return result.distinct().take(500)
    }

    private fun extractImports(lines: List<String>): List<String> {
        return lines.filter { line ->
            IMPORT_REGEX.containsMatchIn(line)
        }.map { it.trim() }.distinct()
    }

    private fun extractFunctions(lines: List<String>): List<String> {
        return lines.filter { line ->
            val trimmed = line.trim()
            val isComment = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("#")
            !isComment && FUN_REGEX.containsMatchIn(trimmed)
        }.map { it.trim() }.distinct()
    }

    private fun extractClasses(lines: List<String>): List<String> {
        return lines.filter { line ->
            val trimmed = line.trim()
            val isComment = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("#")
            !isComment && CLASS_REGEX.containsMatchIn(trimmed)
        }.map { it.trim() }.distinct()
    }

    private fun searchInIndex(fileIndex: FileIndex, query: String): List<ContextResult> {
        val results = mutableListOf<ContextResult>()
        val lowerQuery = query.lowercase()

        // Search strings
        fileIndex.strings.forEachIndexed { idx, str ->
            val score = scoreRelevance(str, lowerQuery)
            if (score > 0f) {
                results.add(
                    ContextResult(
                        filePath = fileIndex.path,
                        relevanceScore = score,
                        snippet = str,
                        lineNumber = idx + 1,
                        matchType = resolveMatchType(score)
                    )
                )
            }
        }

        // Search imports
        fileIndex.imports.forEach { imp ->
            val score = scoreRelevance(imp, lowerQuery)
            if (score > 0f) {
                results.add(
                    ContextResult(
                        filePath = fileIndex.path,
                        relevanceScore = score,
                        snippet = imp,
                        matchType = resolveMatchType(score)
                    )
                )
            }
        }

        // Search functions
        fileIndex.functions.forEach { func ->
            val score = scoreRelevance(func, lowerQuery)
            if (score > 0f) {
                results.add(
                    ContextResult(
                        filePath = fileIndex.path,
                        relevanceScore = score,
                        snippet = func,
                        matchType = resolveMatchType(score)
                    )
                )
            }
        }

        // Search classes
        fileIndex.classes.forEach { cls ->
            val score = scoreRelevance(cls, lowerQuery)
            if (score > 0f) {
                results.add(
                    ContextResult(
                        filePath = fileIndex.path,
                        relevanceScore = score,
                        snippet = cls,
                        matchType = resolveMatchType(score)
                    )
                )
            }
        }

        return results
    }

    private fun scoreRelevance(text: String, query: String): Float {
        val lowerText = text.lowercase()
        return when {
            lowerText == query -> 1.0f
            lowerText.contains(query) -> 0.7f
            fuzzyMatch(lowerText, query) -> 0.4f
            else -> 0f
        }
    }

    private fun resolveMatchType(score: Float): String {
        return when (score) {
            1.0f -> "exact"
            0.7f -> "contains"
            else -> "fuzzy"
        }
    }

    private fun fuzzyMatch(text: String, query: String): Boolean {
        var textIndex = 0
        for (c in query) {
            val foundIndex = text.indexOf(c, textIndex)
            if (foundIndex == -1) {
                return false
            }
            textIndex = foundIndex + 1
        }
        return true
    }
}
