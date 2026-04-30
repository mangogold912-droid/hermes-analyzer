package com.hermes.analyzer.model

data class DisassemblyLine(
    val address: String,
    val bytes: String,
    val mnemonic: String,
    val operands: String = "",
    val comment: String = ""
)
