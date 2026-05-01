package com.hermes.analyzer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hermes.analyzer.ai.agents.BinaryAnalystAgent
import com.hermes.analyzer.ai.AdvancedAIEngine

/**
 * IDAMobileActivity
 * IDA 분석 화면 (바이너리 분석, 문자열, 함수, 의심 함수)
 */
class IDAMobileActivity : AppCompatActivity() {
    private lateinit var engine: AdvancedAIEngine
    private lateinit var binaryAgent: BinaryAnalystAgent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)
        title = "IDA Mobile Analysis"

        engine = AdvancedAIEngine(this)
        binaryAgent = BinaryAnalystAgent(engine)

        root.addView(TextView(this).apply {
            text = "IDA Mobile Binary Analysis"
            textSize = 20f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        })

        // File input
        val etFile = EditText(this).apply { hint = "/path/to/binary.so or .apk" }
        root.addView(etFile)

        root.addView(Button(this).apply {
            text = "Analyze Binary"
            setOnClickListener {
                val path = etFile.text.toString()
                if (path.isNotBlank()) {
                    val result = binaryAgent.analyzeBinary(path)
                    showAnalysisResult(result)
                }
            }
        })

        // Analysis sections
        root.addView(TextView(this).apply {
            text = "Supported Analysis"
            textSize = 16f
            setTextColor(Color.parseColor("#00D4AA"))
            setPadding(32, 24, 32, 8)
        })

        val features = listOf(
            "Function List Extraction",
            "String Table Analysis",
            "Import/Export Symbols",
            "Cross-Reference Detection",
            "Suspicious Function Flow",
            "Network API Call Tracing",
            "Crypto Code Pattern Detection",
            "Decompiler Output Preview"
        )
        features.forEach { f ->
            root.addView(TextView(this).apply {
                text = "• $f"
                textSize = 13f
                setTextColor(Color.LTGRAY)
                setPadding(48, 4, 32, 4)
            })
        }

        root.addView(Button(this).apply {
            text = "Generate IDAPython Script"
            setOnClickListener {
                val script = generateIDAPythonScript()
                AlertDialog.Builder(this@IDAMobileActivity)
                    .setTitle("IDAPython Script")
                    .setMessage(script)
                    .setPositiveButton("Copy", null)
                    .setNegativeButton("Close", null)
                    .show()
            }
        })

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }

    private fun showAnalysisResult(result: BinaryAnalystAgent.BinaryAnalysis) {
        val sb = StringBuilder()
        sb.append("File Type: ${result.fileType}\n\n")
        sb.append("=== Header ===\n")
        result.header.forEach { (k, v) -> sb.append("$k: $v\n") }
        sb.append("\n=== Interesting Strings (${result.strings.size}) ===\n")
        result.strings.take(20).forEach { s ->
            sb.append("[${s.category}] ${s.value.take(60)}\n")
        }
        sb.append("\n=== Suspicious Functions (${result.suspiciousFunctions.size}) ===\n")
        result.suspiciousFunctions.forEach { f ->
            sb.append("${f.severity.uppercase()}: ${f.name} - ${f.reason}\n")
        }

        AlertDialog.Builder(this)
            .setTitle("Analysis Result")
            .setMessage(sb.toString().take(3000))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun generateIDAPythonScript(): String {
        return """# Auto-generated IDAPython Script
import idaapi, idautils, idc

# Get all functions
funcs = []
for ea in idautils.Functions():
    name = idc.get_func_name(ea)
    funcs.append((ea, name))

# Get all strings
strings = []
for s in idautils.Strings():
    strings.append((s.ea, str(s)))

# Get imports
imports = []
for i in range(idaapi.get_import_module_qty()):
    name = idaapi.get_import_module_name(i)
    imports.append(name)

# Suspicious API checks
suspicious = ['socket', 'connect', 'send', 'recv', 'encrypt', 'decrypt', 'dlopen', 'dlsym']

print("Functions: %d" % len(funcs))
print("Strings: %d" % len(strings))
print("Imports: %d" % len(imports))
""".trimIndent()
    }
}
