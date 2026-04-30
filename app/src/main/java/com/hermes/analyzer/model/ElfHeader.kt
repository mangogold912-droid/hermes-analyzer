package com.hermes.analyzer.model

data class ElfHeader(
    val isElf: Boolean = false,
    val bitClass: String = "",
    val endian: String = "",
    val machine: String = "",
    val entryPoint: String = "",
    val phOffset: Long = 0,
    val shOffset: Long = 0,
    val phCount: Int = 0,
    val shCount: Int = 0,
    val shStrIndex: Int = 0,
    val sections: List<ElfSection> = emptyList(),
    val symbols: List<ElfSymbol> = emptyList(),
    val dynamicEntries: List<ElfDynamicEntry> = emptyList()
)

data class ElfSection(
    val name: String,
    val type: String,
    val address: String,
    val offset: Long,
    val size: Long,
    val flags: String
)

data class ElfSymbol(
    val name: String,
    val address: String,
    val size: Long,
    val type: String,
    val binding: String,
    val section: String
)

data class ElfDynamicEntry(
    val tag: String,
    val value: String
)
