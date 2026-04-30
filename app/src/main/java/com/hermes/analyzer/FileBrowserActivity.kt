package com.hermes.analyzer

import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class FileBrowserActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val listView = ListView(this)
        setContentView(listView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this, "All files access not granted!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = Environment.getExternalStorageDirectory()
        val files = root.walkTopDown().filter { it.isFile }.take(1000).toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files.map { it.absolutePath })
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val file = files[position]
            Toast.makeText(this, "Selected: ${file.name} (${file.length()} bytes)", Toast.LENGTH_SHORT).show()
        }
    }
}
