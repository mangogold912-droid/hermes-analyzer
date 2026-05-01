package com.hermes.analyzer

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hermes.analyzer.ai.UnifiedAIEngine
import com.hermes.analyzer.db.ChatHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatGPTActivity : AppCompatActivity() {
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private val aiEngine by lazy { UnifiedAIEngine(this) }
    private val chatHistory by lazy { ChatHistoryManager.getInstance(this) }
    private var currentSessionId: String? = null
    private var currentFilePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this)
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(chatContainer)
        layout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1.0f
        ))

        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        inputEditText = EditText(this).apply {
            hint = "메시지를 입력하세요..."
        }
        inputLayout.addView(inputEditText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        ))

        sendButton = Button(this).apply {
            text = "전송"
            setOnClickListener { sendMessage() }
        }
        inputLayout.addView(sendButton)
        layout.addView(inputLayout)

        setContentView(layout)

        addAIMessage("Hermes AI에 오신 것을 환영합니다. 파일을 업로드하시거나 질문을 입력해 주세요.")
    }

    private fun sendMessage() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty()) return

        addUserMessage(text)
        inputEditText.setText("")

        lifecycleScope.launch {
            val response = withContext(Dispatchers.IO) {
                aiEngine.process(text, currentFilePath, currentSessionId)
            }
            addAIMessage(response)
        }
    }

    private fun addUserMessage(text: String) {
        val tv = TextView(this).apply {
            this.text = "You: $text"
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xFFE3F2FD.toInt())
        }
        chatContainer.addView(tv)
    }

    private fun addAIMessage(text: String) {
        val tv = TextView(this).apply {
            this.text = "AI: $text"
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        chatContainer.addView(tv)
    }
}
