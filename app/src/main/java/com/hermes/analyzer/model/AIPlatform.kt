package com.hermes.analyzer.model

data class AIPlatform(
    val name: String,
    val displayName: String,
    var enabled: Boolean = true,
    var apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val isLocal: Boolean = false
)
