package com.hermes.analyzer.ai

import java.io.File

/**
 * HexAnalyzer
 * 전문 파일 오프셋 분석, 헥스 덤프, 구조체 파싱
 */
class HexAnalyzer {

    data class HexDumpLine(
        val offset: Long,
        val hexBytes: List<String>,
        val ascii: String
    )

    data class StructureField(
        val name: String,
        val offset: Long,
        val size: Int,
        val type: String,
        val value: String,
        val description: String
    )

    data class OffsetAnalysis(
        val filePath: String,
        val fileSize: Long,
        val magic: String,
        val architecture: String,
        val entryPoint: String?,
        val sections: List<StructureField>,
        val interestingOffsets: List<InterestingOffset>
    )

    data class InterestingOffset(
        val offset: Long,
        val type: String,
        val description: String,
        val hexPreview: String
    )

    fun hexDump(filePath: String, startOffset: Long = 0, lines: Int = 32): List<HexDumpLine> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()

        val result = mutableListOf<HexDumpLine>()
        file.inputStream().use { stream ->
            stream.skip(startOffset)
            val buffer = ByteArray(16)
            var offset = startOffset
            var lineCount = 0

            while (lineCount < lines) {
                val read = stream.read(buffer)
                if (read <= 0) break

                val hexBytes = (0 until read).map { "%02X".format(buffer[it]) }
                val ascii = (0 until read).map {
                    val b = buffer[it]
                    if (b in 32..126) b.toChar().toString() else "."
                }.joinToString("")

                result.add(HexDumpLine(offset, hexBytes, ascii))
                offset += read
                lineCount++
            }
        }
        return result
    }

    fun analyzeOffsets(filePath: String): OffsetAnalysis {
        val file = File(filePath)
        if (!file.exists()) {
            return OffsetAnalysis(filePath, 0, "", "unknown", null, emptyList(), emptyList())
        }

        val size = file.length()
        val magic = readMagic(filePath)
        val arch = detectArchitecture(filePath, magic)
        val entry = extractEntryPoint(filePath, magic)
        val sections = parseStructure(filePath, magic)
        val interesting = findInterestingOffsets(filePath, size, magic)

        return OffsetAnalysis(filePath, size, magic, arch, entry, sections, interesting)
    }

    fun searchPattern(filePath: String, pattern: ByteArray): List<Long> {
        val result = mutableListOf<Long>()
        val file = File(filePath)
        if (!file.exists()) return result

        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var offset: Long = 0
            var bytesRead: Int
            val window = ByteArray(pattern.size)
            var windowPos = 0

            while (stream.read(buffer).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead) {
                    window[windowPos % pattern.size] = buffer[i]
                    windowPos++
                    if (windowPos >= pattern.size) {
                        var match = true
                        for (j in pattern.indices) {
                            if (window[(windowPos - pattern.size + j) % pattern.size] != pattern[j]) {
                                match = false
                                break
                            }
                        }
                        if (match) {
                            result.add(offset + i - pattern.size + 1)
                        }
                    }
                }
                offset += bytesRead
            }
        }
        return result
    }

    fun findStrings(filePath: String, minLength: Int = 4): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        val file = File(filePath)
        if (!file.exists()) return result

        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var offset: Long = 0
            var currentString = StringBuilder()
            var stringStart: Long = 0
            var bytesRead: Int

            while (stream.read(buffer).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead) {
                    val b = buffer[i]
                    if (b in 32..126) {
                        if (currentString.isEmpty()) stringStart = offset + i
                        currentString.append(b.toChar())
                    } else {
                        if (currentString.length >= minLength) {
                            result.add(stringStart to currentString.toString())
                        }
                        currentString.clear()
                    }
                }
                offset += bytesRead
            }
            if (currentString.length >= minLength) {
                result.add(stringStart to currentString.toString())
            }
        }
        return result
    }

    fun disassembleAtOffset(filePath: String, offset: Long, count: Int = 10): String {
        val file = File(filePath)
        if (!file.exists()) return "File not found"

        return try {
            file.inputStream().use { stream ->
                stream.skip(offset)
                val bytes = stream.readNBytes(minOf(count * 4, 64))
                val sb = StringBuilder()
                sb.append("Offset 0x${offset.toString(16).uppercase().padStart(8, '0')}:\n")
                bytes.toList().chunked(4).forEach { chunk ->
                    val hex = chunk.joinToString(" ") { "%02X".format(it) }
                    sb.append("  $hex\n")
                }
                sb.toString()
            }
        } catch (e: Exception) {
            "Error reading at offset: ${e.message}"
        }
    }

    private fun readMagic(filePath: String): String {
        return try {
            File(filePath).inputStream().use { it.readNBytes(16).joinToString(" ") { b -> "%02X".format(b) } }
        } catch (e: Exception) { "" }
    }

    private fun detectArchitecture(filePath: String, magic: String): String {
        return when {
            magic.startsWith("7F 45 4C 46") -> {
                val archByte = try {
                    File(filePath).inputStream().use { it.skip(4); it.read() }
                } catch (e: Exception) { 0 }
                when (archByte) {
                    62 -> "ELF x86-64"
                    40 -> "ELF ARM"
                    183 -> "ELF AArch64"
                    else -> "ELF Unknown"
                }
            }
            magic.startsWith("4D 5A") -> "PE/Windows"
            magic.startsWith("CA FE BA BE") -> "Java Class"
            magic.startsWith("50 4B 03 04") -> "ZIP/APK"
            magic.startsWith("64 65 78 0A") -> "DEX"
            else -> "Unknown"
        }
    }

    private fun extractEntryPoint(filePath: String, magic: String): String? {
        return try {
            when {
                magic.startsWith("7F 45 4C 46") -> {
                    File(filePath).inputStream().use { stream ->
                        stream.skip(24)
                        val entry = ByteArray(8)
                        stream.read(entry)
                        val addr = entry.foldRight(0L) { b, acc -> (acc shl 8) or (b.toLong() and 0xFF) }
                        "0x${addr.toString(16).uppercase().padStart(16, '0')}"
                    }
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun parseStructure(filePath: String, magic: String): List<StructureField> {
        val fields = mutableListOf<StructureField>()
        when {
            magic.startsWith("7F 45 4C 46") -> {
                fields.add(StructureField("Magic", 0, 4, "byte[4]", "7F 45 4C 46", "ELF magic number"))
                fields.add(StructureField("Class", 4, 1, "uint8", "32/64-bit", "Architecture class"))
                fields.add(StructureField("Endian", 5, 1, "uint8", "Little/Big", "Data encoding"))
                fields.add(StructureField("Version", 6, 1, "uint8", "1", "ELF version"))
                fields.add(StructureField("OS/ABI", 7, 1, "uint8", "System V", "Target OS"))
                fields.add(StructureField("Type", 16, 2, "uint16", "EXEC/DYN", "Object file type"))
                fields.add(StructureField("Machine", 18, 2, "uint16", "x86/ARM", "Architecture"))
                fields.add(StructureField("Entry Point", 24, 8, "uint64", "See value", "Entry point address"))
                fields.add(StructureField("Program Header Offset", 32, 8, "uint64", "See value", "PH offset"))
                fields.add(StructureField("Section Header Offset", 40, 8, "uint64", "See value", "SH offset"))
            }
            magic.startsWith("50 4B 03 04") -> {
                fields.add(StructureField("Local Header", 0, 30, "struct", "PK\u0003\u0004", "ZIP local file header"))
                fields.add(StructureField("Version", 4, 2, "uint16", "See value", "ZIP version"))
                fields.add(StructureField("Flags", 6, 2, "uint16", "See value", "General purpose flags"))
                fields.add(StructureField("Compression", 8, 2, "uint16", "0/8", "Compression method"))
                fields.add(StructureField("CRC-32", 14, 4, "uint32", "See value", "CRC checksum"))
                fields.add(StructureField("Compressed Size", 18, 4, "uint32", "See value", "Compressed size"))
                fields.add(StructureField("Uncompressed Size", 22, 4, "uint32", "See value", "Uncompressed size"))
                fields.add(StructureField("File Name Length", 26, 2, "uint16", "See value", "Name length"))
                fields.add(StructureField("Extra Field Length", 28, 2, "uint16", "See value", "Extra length"))
            }
            magic.startsWith("64 65 78 0A") -> {
                fields.add(StructureField("Magic", 0, 4, "byte[4]", "dex\n", "DEX magic"))
                fields.add(StructureField("Version", 4, 4, "byte[4]", "See value", "DEX version string"))
                fields.add(StructureField("Checksum", 8, 4, "uint32", "See value", "Adler32 checksum"))
                fields.add(StructureField("Signature", 12, 20, "byte[20]", "SHA-1", "SHA-1 signature"))
                fields.add(StructureField("File Size", 32, 4, "uint32", "See value", "File size"))
                fields.add(StructureField("Header Size", 36, 4, "uint32", "0x70", "Header size"))
                fields.add(StructureField("Endian Tag", 40, 4, "uint32", "0x12345678", "Endianness tag"))
            }
        }
        return fields
    }

    private fun findInterestingOffsets(filePath: String, size: Long, magic: String): List<InterestingOffset> {
        val offsets = mutableListOf<InterestingOffset>()
        val file = File(filePath)

        // Search for URL patterns
        val urlPattern = "http://".toByteArray()
        searchPattern(filePath, urlPattern).take(5).forEach { off ->
            file.inputStream().use { stream ->
                stream.skip(off)
                val preview = stream.readNBytes(32).joinToString(" ") { "%02X".format(it) }
                offsets.add(InterestingOffset(off, "URL", "HTTP URL pattern", preview))
            }
        }

        // Search for crypto constants
        val cryptoPatterns = listOf(
            "AES".toByteArray() to "AES reference",
            "RSA".toByteArray() to "RSA reference",
            "DES".toByteArray() to "DES reference",
            "MD5".toByteArray() to "MD5 reference",
            "SHA256".toByteArray() to "SHA-256 reference"
        )
        cryptoPatterns.forEach { (pattern, desc) ->
            searchPattern(filePath, pattern).firstOrNull()?.let { off ->
                file.inputStream().use { stream ->
                    stream.skip(off)
                    val preview = stream.readNBytes(16).joinToString(" ") { "%02X".format(it) }
                    offsets.add(InterestingOffset(off, "Crypto", desc, preview))
                }
            }
        }

        // Search for JNI patterns
        searchPattern(filePath, "JNI_OnLoad".toByteArray()).firstOrNull()?.let { off ->
            offsets.add(InterestingOffset(off, "JNI", "JNI_OnLoad function", "Native bridge entry"))
        }

        // ELF specific
        if (magic.startsWith("7F 45 4C 46")) {
            offsets.add(InterestingOffset(0, "ELF", "ELF Header", magic.take(20)))
        }

        return offsets
    }

    fun formatHexDump(lines: List<HexDumpLine>): String {
        val sb = StringBuilder()
        sb.append("        00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  |  ASCII\n")
        sb.append("       ------------------------------------------------  |  ----------------\n")
        lines.forEach { line ->
            val hex = line.hexBytes.joinToString(" ").padEnd(48, ' ')
            val offsetStr = line.offset.toString(16).uppercase().padStart(8, '0')
            sb.append("$offsetStr  $hex  |  ${line.ascii}\n")
        }
        return sb.toString()
    }

    fun formatOffsetAnalysis(analysis: OffsetAnalysis): String {
        val sb = StringBuilder()
        sb.append("# Offset Analysis: ${File(analysis.filePath).name}\n\n")
        sb.append("**File Size**: ${analysis.fileSize} bytes (${"%.2f".format(analysis.fileSize / 1024.0 / 1024.0)} MB)\n")
        sb.append("**Magic**: ${analysis.magic}\n")
        sb.append("**Architecture**: ${analysis.architecture}\n")
        analysis.entryPoint?.let { sb.append("**Entry Point**: $it\n") }
        sb.append("\n## Structure Fields\n\n")
        sb.append("| Offset | Name | Type | Size | Description |\n")
        sb.append("|--------|------|------|------|-------------|\n")
        analysis.sections.forEach { f ->
            sb.append("| 0x${f.offset.toString(16).uppercase().padStart(8, '0')} | ${f.name} | ${f.type} | ${f.size} | ${f.description} |\n")
        }
        sb.append("\n## Interesting Offsets\n\n")
        analysis.interestingOffsets.forEach { io ->
            sb.append("- **0x${io.offset.toString(16).uppercase().padStart(8, '0')}** [${io.type}]: ${io.description}\n")
            sb.append("  Preview: `${io.hexPreview}`\n")
        }
        return sb.toString()
    }
}
