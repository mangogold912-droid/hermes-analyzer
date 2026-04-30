package com.hermes.analyzer.model

data class ApkInfo(
    val entries: List<ApkEntry> = emptyList(),
    val manifest: String = "",
    val permissions: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val receivers: List<String> = emptyList(),
    val nativeLibraries: List<String> = emptyList(),
    val dexClasses: List<String> = emptyList()
)

data class ApkEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean
)
