package com.hermes.analyzer.model

data class DexHeader(
    val isDex: Boolean = false,
    val version: String = "",
    val fileSize: Int = 0,
    val checksum: Int = 0,
    val stringIdsSize: Int = 0,
    val typeIdsSize: Int = 0,
    val protoIdsSize: Int = 0,
    val fieldIdsSize: Int = 0,
    val methodIdsSize: Int = 0,
    val classDefsSize: Int = 0,
    val strings: List<String> = emptyList(),
    val classes: List<DexClass> = emptyList(),
    val methods: List<DexMethod> = emptyList()
)

data class DexClass(
    val className: String,
    val superClass: String,
    val accessFlags: String,
    val methods: List<DexMethod> = emptyList()
)

data class DexMethod(
    val name: String,
    val className: String,
    val proto: String,
    val accessFlags: String
)
