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
    private val messages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        aiEngine = AIMultiEngine(this)
        val platforms = aiEngine.getPlatforms().filter { it.enabled }

        // Root layout
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Title
        root.addView(TextView(this).apply {
            text = "AI Chat"
            textSize = 24f
            setPadding(16, 32, 16, 16)
        })

        // Platform spinner
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, platforms.map { it.displayName }.toTypedArray())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        root.addView(spinner)

        // Chat display
        val tvChat = TextView(this).apply {
            text = "Chat started. Ask about binary analysis!"
            setPadding(16, 16, 16, 16)
        }
        val scrollView = ScrollView(this)
        scrollView.addView(tvChat)
        root.addView(scrollView, LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        // Input row
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etMessage = EditText(this).apply {
            hint = "Ask AI..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(etMessage)

        val btnSend = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val text = etMessage.text.toString().trim()
                if (text.isEmpty()) return@setOnClickListener
                val platformName = platforms.getOrNull(spinner.selectedItemPosition)?.name ?: return@setOnClickListener

                messages.add(ChatMessage(role = "user", content = text, platformName = platformName))
                tvChat.append("\n\nYou: $text")
                etMessage.setText("")

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val response = aiEngine.chat(platformName, messages.toList())
                        messages.add(ChatMessage(role = "assistant", content = response, platformName = platformName))
                        withContext(Dispatchers.Main) {
                            tvChat.append("\n\n${platformName}: $response")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            tvChat.append("\n\nError: ${e.message}")
                        }
                    }
                }
            }
        }
        inputRow.addView(btnSend)
        root.addView(inputRow)

        setContentView(root)
    }
}
