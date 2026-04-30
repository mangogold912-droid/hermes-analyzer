package com.hermes.analyzer.model

data class ChatMessage(
    val id: String = System.currentTimeMillis().toString(),
    val role: String,
    val content: String,
    val platformName: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
