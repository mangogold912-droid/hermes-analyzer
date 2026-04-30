package com.hermes.analyzer

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hermes.analyzer.ai.AIMultiEngine
import com.hermes.analyzer.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var aiEngine: AIMultiEngine
    private lateinit var lvChat: ListView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var spinnerPlatform: Spinner

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        aiEngine = AIMultiEngine(this)

        lvChat = findViewById(R.id.lvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        spinnerPlatform = findViewById(R.id.spinnerPlatform)

        // Platform selector
        val platforms = aiEngine.getPlatforms().filter { it.enabled }
        val platformNames = platforms.map { it.displayName }.toTypedArray()
        val platformKeys = platforms.map { it.name }

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, platformNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPlatform.adapter = spinnerAdapter

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvChat.adapter = adapter

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val platformIdx = spinnerPlatform.selectedItemPosition
            val platformName = platformKeys[platformIdx]

            // Add user message
            addMessage("You: $text")
            messages.add(ChatMessage(role = "user", content = text, platformName = platformName))
            etMessage.setText("")

            // Send to AI
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val response = aiEngine.chat(platformName, messages.toList())
                    withContext(Dispatchers.Main) {
                        messages.add(ChatMessage(role = "assistant", content = response, platformName = platformName))
                        addMessage("${platforms[platformIdx].displayName}: $response")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        addMessage("Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun addMessage(text: String) {
        adapter.add(text)
        adapter.notifyDataSetChanged()
        lvChat.setSelection(adapter.count - 1)
    }
}
