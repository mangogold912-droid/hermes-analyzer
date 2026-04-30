package com.hermes.analyzer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hermes.analyzer.ai.AIMultiEngine
import com.hermes.analyzer.db.AnalysisDatabase
import com.hermes.analyzer.model.*
import com.hermes.analyzer.network.IDAMCPClient
import com.hermes.analyzer.utils.BinaryAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var db: AnalysisDatabase
    private lateinit var aiEngine: AIMultiEngine
    private lateinit var idaClient: IDAMCPClient
    private lateinit var analyzer: BinaryAnalyzer

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSelectFile: Button
    private lateinit var btnConnectIda: Button
    private lateinit var btnAnalyze: Button
    private lateinit var btnChat: Button
    private lateinit var btnSettings: Button
    private lateinit var tvFileInfo: TextView

    private var selectedFile: File? = null
    private var selectedFileType: String = ""

    companion object {
        private const val REQ_STORAGE = 100
        private const val REQ_PICK_FILE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AnalysisDatabase(this)
        aiEngine = AIMultiEngine(this)
        idaClient = IDAMCPClient()
        analyzer = BinaryAnalyzer()

        initViews()
        checkPermissions()

        idaClient.onProgress = { percent, msg ->
            runOnUiThread {
                tvStatus.text = "IDA: $msg ($percent%)"
                progressBar.progress = percent
            }
        }

        aiEngine.onProgress = { percent, msg ->
            runOnUiThread {
                tvStatus.text = "AI: $msg ($percent%)"
                progressBar.progress = percent
            }
        }

        aiEngine.onResult = { platform, result ->
            runOnUiThread { appendLog("[$platform] confidence=${(result.confidence * 100).toInt()}%") }
        }

        loadRecentFiles()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        progressBar = findViewById(R.id.progressBar)
        btnSelectFile = findViewById(R.id.btnSelectFile)
        btnConnectIda = findViewById(R.id.btnConnectIda)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnChat = findViewById(R.id.btnChat)
        btnSettings = findViewById(R.id.btnSettings)
        tvFileInfo = findViewById(R.id.tvFileInfo)

        btnSelectFile.setOnClickListener { pickFile() }
        btnConnectIda.setOnClickListener { connectIda() }
        btnAnalyze.setOnClickListener { startAnalysis() }
        btnChat.setOnClickListener { openChat() }
        btnSettings.setOnClickListener { openSettings() }
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_STORAGE)
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri -> handleFile(uri) }
        }
    }

    private fun handleFile(uri: Uri) {
        try {
            val name = getFileName(uri)
            val fileType = analyzer.detectFileType(name)
            val cacheFile = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            }
            selectedFile = cacheFile
            selectedFileType = fileType
            val hash = analyzer.computeHash(cacheFile.absolutePath)
            val size = cacheFile.length()
            tvFileInfo.text = """Name: $name
Type: ${fileType.uppercase()}
Size: ${formatSize(size)}
SHA256: ${hash.take(16)}..."""
            appendLog("File loaded: $name ($fileType)")
        } catch (e: Exception) {
            appendLog("Error: ${e.message}")
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx) ?: "unknown"
            }
        }
        return name
    }

    private fun connectIda() {
        val prefs = getSharedPreferences("hermes_settings", MODE_PRIVATE)
        val host = prefs.getString("ida_host", "192.168.1.100") ?: "192.168.1.100"
        val port = prefs.getInt("ida_port", 8080)
        AlertDialog.Builder(this)
            .setTitle("Connect to IDA Pro MCP")
            .setMessage("Connect to $host:$port?")
            .setPositiveButton("Connect") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = idaClient.connect(host, port)
                    runOnUiThread {
                        tvStatus.text = if (ok) "IDA Connected!" else "IDA Connection Failed"
                        Toast.makeText(this@MainActivity, if (ok) "Connected!" else "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAnalysis() {
        val file = selectedFile ?: run {
            Toast.makeText(this, "Select a file first!", Toast.LENGTH_SHORT).show()
            return
        }
        val jobId = db.insertJob(AnalysisJob(fileId = 1, jobType = "full"))
        tvStatus.text = "Analysis starting..."
        progressBar.progress = 0
        tvLog.text = ""
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val results = aiEngine.analyzeFile(file.absolutePath, selectedFileType, "full", jobId)
                for (result in results) db.insertResult(result)
                db.updateJobStatus(jobId, "completed", 100)
                val consensus = results.find { it.platformName == "consensus" }
                val summary = try { JSONObject(consensus?.content ?: "{}").optString("summary", "Done") } catch (_: Exception) { "Done" }
                runOnUiThread {
                    tvStatus.text = "Complete!"
                    progressBar.progress = 100
                    appendLog("=== $summary ===")
                    results.filter { it.platformName != "consensus" }.forEach {
                        appendLog("[${it.platformName}] ${(it.confidence * 100).toInt()}% ${it.processingTime}ms")
                    }
                }
            } catch (e: Exception) {
                db.updateJobStatus(jobId, "failed", error = e.message)
                runOnUiThread {
                    tvStatus.text = "Failed"
                    appendLog("ERROR: ${e.message}")
                }
            }
        }
    }

    private fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java))
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun loadRecentFiles() {
        val files = db.getRecentFiles(5)
        if (files.isNotEmpty()) {
            appendLog("Recent files:")
            files.forEach { appendLog("  - ${it.originalName} (${it.fileType})") }
        }
    }

    private fun appendLog(msg: String) {
        tvLog.append("$msg\n")
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        idaClient.disconnect()
    }
}
