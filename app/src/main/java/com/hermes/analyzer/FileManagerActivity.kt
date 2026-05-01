package com.hermes.analyzer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.webkit.MimeTypeMap
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * FileManagerActivity
 * 통합 파일 관리자 (탐색, 삭제, 공유, 압축 해제, 미리보기)
 */
class FileManagerActivity : AppCompatActivity() {
    private var currentDir: File = File("/storage/emulated/0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        setContentView(scroll)
        title = "File Manager"

        refresh(root)
    }

    private fun refresh(root: LinearLayout) {
        root.removeAllViews()

        root.addView(TextView(this).apply {
            text = currentDir.absolutePath
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(32, 16, 32, 8)
        })

        val files = (currentDir.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 12, 32, 12)
                setBackgroundColor(if (file.isDirectory) Color.parseColor("#1A1A2E") else Color.parseColor("#1E1E1E"))
            }
            val icon = if (file.isDirectory) "📁" else getFileIcon(file.name)
            row.addView(TextView(this).apply {
                text = "$icon ${file.name}"
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = if (file.isFile) formatSize(file.length()) else "${file.list()?.size ?: 0} items"
                textSize = 11f
                setTextColor(Color.GRAY)
            })
            row.setOnClickListener {
                if (file.isDirectory) {
                    currentDir = file
                    refresh(root)
                } else {
                    showFileOptions(file)
                }
            }
            root.addView(row)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.DKGRAY)
            })
        }

        if (currentDir != File("/storage/emulated/0")) {
            root.addView(Button(this).apply {
                text = "← Parent Directory"
                setOnClickListener {
                    currentDir.parentFile?.let { currentDir = it; refresh(root) }
                }
            })
        }

        root.addView(Button(this).apply {
            text = "Back to Main"
            setOnClickListener { finish() }
            setPadding(32, 16, 32, 48)
        })
    }

    private fun showFileOptions(file: File) {
        val options = arrayOf("Open", "Share", "Delete", "Analyze with AI", "Properties")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "Open: ${file.name}", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this, "Share: ${file.name}", Toast.LENGTH_SHORT).show()
                    2 -> {
                        file.delete()
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        refresh(findViewById(android.R.id.content).findViewById(android.R.id.content))
                    }
                    3 -> Toast.makeText(this, "Send to AI Chat for analysis", Toast.LENGTH_SHORT).show()
                    4 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Properties")
                            .setMessage("Name: ${file.name}\nSize: ${formatSize(file.length())}\nPath: ${file.absolutePath}\nLast Modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(file.lastModified()))}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun getFileIcon(name: String): String {
        return when {
            name.endsWith(".apk") -> "📦"
            name.endsWith(".so") || name.endsWith(".elf") -> "⚙️"
            name.endsWith(".dex") -> "☕"
            name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") -> "🗜️"
            name.endsWith(".py") -> "🐍"
            name.endsWith(".js") || name.endsWith(".ts") -> "📜"
            name.endsWith(".java") || name.endsWith(".kt") -> "☕"
            name.endsWith(".json") -> "📋"
            name.endsWith(".xml") -> "📄"
            name.endsWith(".md") || name.endsWith(".txt") -> "📝"
            name.endsWith(".pdf") -> "📕"
            name.endsWith(".jpg") || name.endsWith(".png") -> "🖼️"
            else -> "📄"
        }
    }

    private fun formatSize(size: Long): String {
        return when {
            size > 1024 * 1024 * 1024 -> "%.2f GB".format(size / (1024.0 * 1024.0 * 1024.0))
            size > 1024 * 1024 -> "%.2f MB".format(size / (1024.0 * 1024.0))
            size > 1024 -> "%.2f KB".format(size / 1024.0)
            else -> "$size B"
        }
    }
}
