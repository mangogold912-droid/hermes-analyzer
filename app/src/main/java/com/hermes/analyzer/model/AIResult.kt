package com.hermes.analyzer.model

data class AIResult(
    val id: Long = 0,
    val jobId: Long,
    val platformName: String,
    val resultType: String,
    val content: String,
    val rawText: String = "",
    val confidence: Float = 0.5f,
    val processingTime: Long = 0
)
