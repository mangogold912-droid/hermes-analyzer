package com.hermes.analyzer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class FilePickerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Delegates to MainActivity for file picking
        val intent = android.content.Intent(this, MainActivity::class.java)
        intent.putExtra("openPicker", true)
        startActivity(intent)
        finish()
    }
}
