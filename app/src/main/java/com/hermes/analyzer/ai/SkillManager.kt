package com.hermes.analyzer.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SkillManager
 * AI가 필요한 스킬/도구를 자동으로 생성, 저장, 실행하는 시스템
 */
class SkillManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_skills", Context.MODE_PRIVATE)
    private val skillsDir = File(context.getExternalFilesDir(null), "skills").apply { mkdirs() }

    data class Skill(
        val id: String,
        val name: String,
        val description: String,
        val language: String, // kotlin, python, bash, javascript
        val code: String,
        val inputSchema: Map<String, String>,
        val outputSchema: String,
        val tags: List<String>,
        val createdAt: Long,
        var usageCount: Int = 0,
        var avgExecutionTimeMs: Long = 0
    )

    fun createSkill(
        name: String,
        description: String,
        language: String,
        code: String,
        inputs: Map<String, String>,
        output: String,
        tags: List<String>
    ): String {
        val id = "skill_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val skill = Skill(id, name, description, language, code, inputs, output, tags, System.currentTimeMillis())
        saveSkill(skill)
        return id
    }

    fun executeSkill(skillId: String, inputs: Map<String, String>): String {
        val skill = getSkill(skillId) ?: return "Error: Skill not found"
        val start = System.currentTimeMillis()

        val result = try {
            when (skill.language) {
                "bash", "sh" -> executeBash(skill.code, inputs)
                "python" -> executePython(skill.code, inputs)
                "javascript", "js" -> executeJavaScript(skill.code, inputs)
                else -> "Error: Unsupported language ${skill.language}"
            }
        } catch (e: Exception) {
            "Execution error: ${e.message}"
        }

        val duration = System.currentTimeMillis() - start
        updateSkillStats(skillId, duration)
        return result
    }

    fun generateSkillFromDescription(description: String, language: String = "bash"): String {
        // AI가 자연어 설명을 바탕으로 코드를 생성
        val generatedCode = when {
            description.contains("hex", ignoreCase = true) || description.contains("dump", ignoreCase = true) ->
                generateHexDumpSkill()
            description.contains("string", ignoreCase = true) || description.contains("extract", ignoreCase = true) ->
                generateStringExtractSkill()
            description.contains("hash", ignoreCase = true) || description.contains("md5", ignoreCase = true) || description.contains("sha", ignoreCase = true) ->
                generateHashSkill()
            description.contains("base64", ignoreCase = true) || description.contains("encode", ignoreCase = true) ->
                generateBase64Skill()
            description.contains("port", ignoreCase = true) || description.contains("scan", ignoreCase = true) ->
                generatePortScanSkill()
            else -> generateGenericSkill(description)
        }

        return createSkill(
            name = "Auto: ${description.take(30)}...",
            description = description,
            language = language,
            code = generatedCode,
            inputs = mapOf("input" to "string"),
            output = "string",
            tags = listOf("auto-generated", language)
        )
    }

    fun getSkill(id: String): Skill? {
        val raw = prefs.getString("skill_$id", null) ?: return null
        return parseSkill(JSONObject(raw))
    }

    fun getAllSkills(): List<Skill> {
        return prefs.all.keys.filter { it.startsWith("skill_") }.mapNotNull { key ->
            prefs.getString(key, null)?.let { parseSkill(JSONObject(it)) }
        }.sortedByDescending { it.usageCount }
    }

    fun searchSkills(query: String): List<Skill> {
        val lower = query.lowercase()
        return getAllSkills().filter { skill ->
            skill.name.lowercase().contains(lower) ||
            skill.description.lowercase().contains(lower) ||
            skill.tags.any { it.lowercase().contains(lower) }
        }
    }

    fun deleteSkill(id: String) {
        prefs.edit().remove("skill_$id").apply()
        File(skillsDir, "$id.code").delete()
    }

    fun getSkillStats(): String {
        val skills = getAllSkills()
        val sb = StringBuilder()
        sb.append("# Skill Manager Report\n\n")
        sb.append("Total skills: ${skills.size}\n")
        sb.append("Most used: ${skills.firstOrNull()?.name ?: "None"}\n\n")
        sb.append("## Skills\n\n")
        skills.forEach { s ->
            sb.append("- **${s.name}** (${s.language}) - Used ${s.usageCount} times\n")
            sb.append("  ${s.description.take(80)}...\n")
        }
        return sb.toString()
    }

    private fun executeBash(code: String, inputs: Map<String, String>): String {
        var script = code
        inputs.forEach { (k, v) -> script = script.replace("\${$k}", v) }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", script))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (error.isNotEmpty()) "Error: $error" else output
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun executePython(code: String, inputs: Map<String, String>): String {
        // Python execution requires Python runtime - return simulated result
        return """## Python Execution
Input variables: ${inputs.entries.joinToString { "${it.key}=${it.value.take(50)}" }}
Code length: ${code.length} chars
Status: Python execution requires local Python interpreter (Termux/pydroid3)
"""
    }

    private fun executeJavaScript(code: String, inputs: Map<String, String>): String {
        return """## JavaScript Execution
Input variables: ${inputs.entries.joinToString { "${it.key}=${it.value.take(50)}" }}
Code length: ${code.length} chars
Status: JavaScript execution requires Node.js runtime
"""
    }

    private fun saveSkill(skill: Skill) {
        val obj = JSONObject().apply {
            put("id", skill.id)
            put("name", skill.name)
            put("description", skill.description)
            put("language", skill.language)
            put("code", skill.code)
            put("inputs", JSONObject(skill.inputSchema))
            put("output", skill.outputSchema)
            put("tags", JSONArray(skill.tags))
            put("createdAt", skill.createdAt)
            put("usageCount", skill.usageCount)
            put("avgTime", skill.avgExecutionTimeMs)
        }
        prefs.edit().putString("skill_${skill.id}", obj.toString()).apply()
        File(skillsDir, "${skill.id}.code").writeText(skill.code)
    }

    private fun updateSkillStats(id: String, duration: Long) {
        val raw = prefs.getString("skill_$id", null) ?: return
        val obj = JSONObject(raw)
        val count = obj.optInt("usageCount", 0) + 1
        val avg = obj.optLong("avgTime", 0)
        obj.put("usageCount", count)
        obj.put("avgTime", (avg * (count - 1) + duration) / count)
        prefs.edit().putString("skill_$id", obj.toString()).apply()
    }

    private fun parseSkill(obj: JSONObject): Skill {
        val inputs = mutableMapOf<String, String>()
        val inputObj = obj.optJSONObject("inputs")
        inputObj?.let {
            it.keys().forEach { k -> inputs[k] = it.getString(k) }
        }
        return Skill(
            obj.getString("id"),
            obj.getString("name"),
            obj.getString("description"),
            obj.getString("language"),
            obj.getString("code"),
            inputs,
            obj.optString("output", "string"),
            obj.optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            obj.getLong("createdAt"),
            obj.optInt("usageCount", 0),
            obj.optLong("avgTime", 0)
        )
    }

    // Built-in skill generators
    private fun generateHexDumpSkill(): String = """#!/system/bin/sh
xxd -l 256 "\${input}" 2>/dev/null || hexdump -C "\${input}" | head -20"""

    private fun generateStringExtractSkill(): String = """#!/system/bin/sh
strings "\${input}" | head -50"""

    private fun generateHashSkill(): String = """#!/system/bin/sh
md5sum "\${input}" && sha256sum "\${input}""""

    private fun generateBase64Skill(): String = """#!/system/bin/sh
if [ -f "\${input}" ]; then base64 "\${input}"; else echo "\${input}" | base64; fi"""

    private fun generatePortScanSkill(): String = """#!/system/bin/sh
for port in 22 80 443 8080 3000; do
  timeout 1 bash -c "echo >/dev/tcp/\${input}/\$port" 2>/dev/null && echo "Port \$port: OPEN" || echo "Port \$port: CLOSED"
done"""

    private fun generateGenericSkill(description: String): String = """#!/system/bin/sh
echo "Auto-generated skill for: $description"
echo "Input received: \${input}"
echo "Processing..."
ls -la "\${input}" 2>/dev/null || echo "\${input}"""
}
