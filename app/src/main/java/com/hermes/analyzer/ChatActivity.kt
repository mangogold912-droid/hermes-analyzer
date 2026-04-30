package com.hermes.analyzer

import android.os.Bundle
import android.view.ViewGroup
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

        val rootLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        aiEngine = AIMultiEngine(this)

        // Title
        val tvTitle = TextView(this).apply {
            text = "AI Chat"
            textSize = 24f
            setPadding(16, 32, 16, 16)
        }
        rootLayout.addView(tvTitle)

        // Platform spinner
        val platforms = aiEngine.getPlatforms().filter { it.enabled }
        val platformNames = platforms.map { it.displayName }.toTypedArray()
        val platformKeys = platforms.map { it.name }

        spinnerPlatform = Spinner(this).apply {
            val sa = ArrayAdapter(this@ChatActivity, android.R.layout.simple_spinner_item, platformNames)
            sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            adapter = sa
        }
        rootLayout.addView(spinnerPlatform)

        // Chat list
        val chatAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        adapter = chatAdapter
        lvChat = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            dividerHeight = 1
        }
        lvChat.adapter = adapter
        rootLayout.addView(lvChat)

        // Input area
        val inputLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etMessage = EditText(this).apply {
            hint = "Ask AI about the binary..."
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSend = Button(this).apply { text = "Send" }
        inputLayout.addView(etMessage)
        inputLayout.addView(btnSend)
        rootLayout.addView(inputLayout)

        setContentView(rootLayout)

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val platformIdx = spinnerPlatform.selectedItemPosition
            val platformName = platformKeys[platformIdx]

            addMessage("You: $text")
            messages.add(ChatMessage(role = "user", content = text, platformName = platformName))
            etMessage.setText("")

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
