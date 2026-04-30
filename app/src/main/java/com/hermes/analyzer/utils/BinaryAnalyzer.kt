package com.hermes.analyzer.utils

import android.util.Log
import com.hermes.analyzer.model.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.ZipFile

class BinaryAnalyzer {
    companion object { private const val TAG = "BinaryAnalyzer" }

    fun extractFeatures(filePath: String, fileType: String): Map<String, Any> {
        val features = mutableMapOf<String, Any>()
        try {
            when (fileType) {
                "elf" -> {
                    features["elfHeader"] = parseElf(filePath)
                    features["strings"] = extractStrings(filePath)
                    features["functions"] = extractSymbols(filePath)
                }
                "dex" -> {
                    features["dexHeader"] = parseDex(filePath)
                    features["strings"] = extractStrings(filePath)
                }
                "apk" -> {
                    features["apkInfo"] = parseApk(filePath)
                    features["strings"] = extractStrings(filePath)
                }
                "so" -> {
                    features["elfHeader"] = parseElf(filePath)
                    features["strings"] = extractStrings(filePath)
                }
                else -> {
                    features["strings"] = extractStrings(filePath)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Feature extraction error: ${e.message}")
            features["strings"] = extractStrings(filePath)
        }
        return features
    }

    fun disassembleArm64(bytes: ByteArray, baseAddress: Long = 0): List<DisassemblyLine> {
        val lines = mutableListOf<DisassemblyLine>()
        var i = 0
        while (i + 3 < bytes.size) {
            val insn = (bytes[i].toInt() and 0xFF) or
                    ((bytes[i+1].toInt() and 0xFF) shl 8) or
                    ((bytes[i+2].toInt() and 0xFF) shl 16) or
                    ((bytes[i+3].toInt() and 0xFF) shl 24)
            val addr = String.format("0x%016X", baseAddress + i)
            val hex = String.format("%02X %02X %02X %02X",
                bytes[i].toInt() and 0xFF, bytes[i+1].toInt() and 0xFF,
                bytes[i+2].toInt() and 0xFF, bytes[i+3].toInt() and 0xFF)
            lines.add(DisassemblyLine(addr, hex, decodeArm64(insn), comment = ""))
            i += 4
        }
        return lines
    }

    fun disassembleX86(bytes: ByteArray, baseAddress: Long = 0): List<DisassemblyLine> {
        val lines = mutableListOf<DisassemblyLine>()
        var i = 0
        while (i < bytes.size) {
            val (mnemonic, len) = decodeX86(bytes, i)
            val addr = String.format("0x%08X", baseAddress + i)
            val hexBytes = bytes.slice(i until minOf(i + len, bytes.size))
                .joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
            lines.add(DisassemblyLine(addr, hexBytes, mnemonic))
            i += len.coerceAtLeast(1)
        }
        return lines
    }

    fun extractStrings(filePath: String, minLen: Int = 4): List<String> {
        val strings = mutableListOf<String>()
        val file = File(filePath)
        val data = ByteArray(minOf(file.length(), 50 * 1024 * 1024).toInt()) // 50MB max
        FileInputStream(file).use { it.read(data) }

        val current = StringBuilder()
        for (b in data) {
            if (b in 0x20..0x7E) {
                current.append(b.toChar())
            } else {
                if (current.length >= minLen) strings.add(current.toString())
                current.clear()
            }
        }
        return strings.distinct()
    }

    fun extractStringsDetailed(filePath: String, minLen: Int = 4): List<ExtractedString> {
        val strings = mutableListOf<ExtractedString>()
        val file = File(filePath)
        val data = ByteArray(minOf(file.length(), 50 * 1024 * 1024).toInt())
        FileInputStream(file).use { it.read(data) }

        val current = StringBuilder()
        var offset = 0L
        for ((i, b) in data.withIndex()) {
            if (b in 0x20..0x7E) {
                if (current.isEmpty()) offset = i.toLong()
                current.append(b.toChar())
            } else {
                if (current.length >= minLen) {
                    strings.add(ExtractedString(String.format("0x%08X", offset), current.toString()))
                }
                current.clear()
            }
        }
        return strings
    }

    fun parseElf(filePath: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val fis = FileInputStream(filePath)
        val header = ByteArray(64)
        fis.read(header)
        fis.close()

        val isElf = header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        result["isElf"] = isElf.toString()

        if (isElf) {
            val bits = header[4].toInt()
            val endian = header[5].toInt()
            val arch = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)

            result["class"] = if (bits == 1) "ELF32" else if (bits == 2) "ELF64" else "Unknown"
            result["endian"] = if (endian == 1) "Little" else if (endian == 2) "Big" else "Unknown"
            result["machine"] = when (arch) {
                0x28 -> "ARM"
                0xB7 -> "AArch64"
                0x03 -> "x86"
                0x3E -> "x86-64"
                0x08 -> "MIPS"
                0x14 -> "PowerPC"
                else -> String.format("0x%04X", arch)
            }

            if (bits == 1) {
                result["entryPoint"] = String.format("0x%08X", read32le(header, 24))
                result["phOffset"] = String.format("0x%08X", read32le(header, 28))
                result["shOffset"] = String.format("0x%08X", read32le(header, 32))
            } else {
                result["entryPoint"] = String.format("0x%016X", read64le(header, 24))
                result["phOffset"] = String.format("0x%016X", read64le(header, 32))
                result["shOffset"] = String.format("0x%016X", read64le(header, 40))
            }

            val phCount = (header[44].toInt() and 0xFF) or ((header[45].toInt() and 0xFF) shl 8)
            val shCount = (header[48].toInt() and 0xFF) or ((header[49].toInt() and 0xFF) shl 8)
            result["phCount"] = phCount.toString()
            result["shCount"] = shCount.toString()
        }
        return result
    }

    fun parseElfFull(filePath: String): ElfHeader {
        val fis = FileInputStream(filePath)
        val header = ByteArray(64)
        fis.read(header)

        val isElf = header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        if (!isElf) return ElfHeader()

        val bits = header[4].toInt()
        val endian = header[5].toInt()
        val arch = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        val is64 = bits == 2

        val entry = if (is64) read64le(header, 24) else (read32le(header, 24).toLong() and 0xFFFFFFFFL)
        val phOff = if (is64) read64le(header, 32) else (read32le(header, 28).toLong() and 0xFFFFFFFFL)
        val shOff = if (is64) read64le(header, 40) else (read32le(header, 32).toLong() and 0xFFFFFFFFL)
        val phEntSize = (header[42].toInt() and 0xFF) or ((header[43].toInt() and 0xFF) shl 8)
        val phNum = (header[44].toInt() and 0xFF) or ((header[45].toInt() and 0xFF) shl 8)
        val shEntSize = (header[46].toInt() and 0xFF) or ((header[47].toInt() and 0xFF) shl 8)
        val shNum = (header[48].toInt() and 0xFF) or ((header[49].toInt() and 0xFF) shl 8)
        val shStrNdx = (header[50].toInt() and 0xFF) or ((header[51].toInt() and 0xFF) shl 8)

        return ElfHeader(
            isElf = true,
            bitClass = if (is64) "ELF64" else "ELF32",
            endian = if (endian == 1) "Little" else "Big",
            machine = when (arch) { 0x28 -> "ARM"; 0xB7 -> "AArch64"; 0x03 -> "x86"; 0x3E -> "x86-64"; else -> String.format("0x%04X", arch) },
            entryPoint = String.format("0x%X", entry),
            phOffset = phOff,
            shOffset = shOff,
            phCount = phNum,
            shCount = shNum,
            shStrIndex = shStrNdx
        )
    }

    fun parseDex(filePath: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val fis = FileInputStream(filePath)
        val header = ByteArray(112)
        fis.read(header)
        fis.close()

        val isDex = header[0] == 'd'.code.toByte() && header[1] == 'e'.code.toByte() &&
                header[2] == 'x'.code.toByte() && header[3] == '
'.code.toByte()
        result["isDex"] = isDex

        if (isDex) {
            result["version"] = "${header[4].toChar()}${header[5].toChar()}${header[6].toChar()}"
            result["checksum"] = read32le(header, 8)
            result["fileSize"] = read32le(header, 32)
            result["headerSize"] = read32le(header, 36)
            result["endianTag"] = String.format("0x%08X", read32le(header, 40))
            result["stringIdsSize"] = read32le(header, 56)
            result["typeIdsSize"] = read32le(header, 64)
            result["protoIdsSize"] = read32le(header, 68)
            result["fieldIdsSize"] = read32le(header, 72)
            result["methodIdsSize"] = read32le(header, 76)
            result["classDefsSize"] = read32le(header, 80)
        }
        return result
    }

    fun parseApk(filePath: String): ApkInfo {
        val entries = mutableListOf<ApkEntry>()
        val nativeLibs = mutableListOf<String>()
        val dexClasses = mutableListOf<String>()

        ZipFile(filePath).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                entries.add(ApkEntry(entry.name, entry.size, entry.isDirectory))
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    nativeLibs.add(entry.name)
                }
                if (entry.name.endsWith(".dex")) {
                    dexClasses.add(entry.name)
                }
            }
        }

        return ApkInfo(
            entries = entries,
            nativeLibraries = nativeLibs,
            dexClasses = dexClasses
        )
    }

    fun computeHash(filePath: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(filePath).use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun detectFileType(fileName: String): String {
        return when {
            fileName.endsWith(".apk", true) -> "apk"
            fileName.endsWith(".elf", true) -> "elf"
            fileName.endsWith(".dex", true) -> "dex"
            fileName.endsWith(".so", true) -> "so"
            fileName.endsWith(".bin", true) -> "bin"
            fileName.endsWith(".jar", true) -> "jar"
            fileName.endsWith(".zip", true) -> "zip"
            fileName.endsWith(".dll", true) -> "dll"
            fileName.endsWith(".exe", true) -> "exe"
            else -> "bin"
        }
    }

    // === Private ===

    private fun extractSymbols(filePath: String): List<String> {
        return try {
            val header = parseElfFull(filePath)
            if (!header.isElf) return emptyList()

            val fis = FileInputStream(filePath)
            val is64 = header.bitClass == "ELF64"
            val shOff = header.shOffset
            val shNum = header.shCount
            val shStrNdx = header.shStrIndex
            val shEntSize = if (is64) 64 else 40

            // Read section headers
            val shHeaders = mutableListOf<ByteArray>()
            for (i in 0 until shNum) {
                val sh = ByteArray(shEntSize)
                shChannelPosition(shOff + i * shEntSize)
                fis.read(sh)
                shHeaders.add(sh)
            }

            // Get section names
            val strTabOff = if (is64) {
                read64le(shHeaders[shStrNdx], 24)
            } else {
                (read32le(shHeaders[shStrNdx], 16).toLong() and 0xFFFFFFFFL)
            }

            val symTabIdx = shHeaders.indexOfFirst { read32le(it, 4) == 2 } // SHT_SYMTAB
            val strTabIdx = shHeaders.indexOfFirst { read32le(it, 4) == 3 && it != shHeaders[shStrNdx] }

            if (symTabIdx < 0 || strTabIdx < 0) {
                fis.close()
                return emptyList()
            }

            val symTabOff = if (is64) read64le(shHeaders[symTabIdx], 24) else (read32le(shHeaders[symTabIdx], 16).toLong() and 0xFFFFFFFFL)
            val symEntSize = if (is64) 24 else 16
            val symCount = read32le(shHeaders[symTabIdx], 20) / symEntSize

            val strTabOff2 = if (is64) read64le(shHeaders[strTabIdx], 24) else (read32le(shHeaders[strTabIdx], 16).toLong() and 0xFFFFFFFFL)
            val strTabSize = read32le(shHeaders[strTabIdx], 20).toLong()

            val strTab = ByteArray(strTabSize.toInt())
            shChannelPosition(strTabOff2)
            fis.read(strTab)

            val symbols = mutableListOf<String>()
            for (i in 1 until symCount) {
                val symOff = symTabOff + i * symEntSize
                val sym = ByteArray(symEntSize)
                shChannelPosition(symOff)
                fis.read(sym)
                val nameOff = read32le(sym, 0)
                if (nameOff in 0 until strTab.size) {
                    val nameEnd = (nameOff until strTab.size).firstOrNull { strTab[it] == 0.toByte() } ?: strTab.size
                    val name = String(strTab, nameOff, nameEnd - nameOff)
                    if (name.isNotEmpty()) symbols.add(name)
                }
            }
            fis.close()
            symbols
        } catch (e: Exception) {
            Log.e(TAG, "Symbol extraction error: ${e.message}")
            emptyList()
        }
    }

    private fun decodeArm64(insn: Int): String {
        val op = (insn ushr 25) and 0x7F
        return when (op) {
            0b1000101 -> "ADD"
            0b1100101 -> "SUB"
            0b1001001 -> "AND"
            0b1011001 -> "ORR"
            0b1101001 -> "EOR"
            0b1111001 -> "LSL"
            0b1001000 -> "MOV"
            0b0001010 -> "B"
            0b0101010 -> "B.cond"
            0b1001010 -> "BL"
            0b1101011 -> "RET"
            0b1011010 -> "CBZ"
            0b1111100 -> "LDR"
            0b1111101 -> "STR"
            0b1100000 -> "LDP"
            0b1010000 -> "STP"
            else -> String.format(".word 0x%08X", insn)
        }
    }

    private fun decodeX86(bytes: ByteArray, offset: Int): Pair<String, Int> {
        if (offset >= bytes.size) return "???" to 1
        val opcode = bytes[offset].toInt() and 0xFF
        return when (opcode) {
            0x55 -> "PUSH RBP" to 1
            in 0x50..0x57 -> "PUSH ${regs[opcode and 7]}" to 1
            in 0x58..0x5F -> "POP ${regs[opcode and 7]}" to 1
            0x90 -> "NOP" to 1
            0xC3 -> "RET" to 1
            0xC2 -> "RET ${imm16(bytes, offset + 1)}" to 3
            0xE8 -> "CALL ${rel32(bytes, offset + 1)}" to 5
            0xE9 -> "JMP ${rel32(bytes, offset + 1)}" to 5
            0xEB -> "JMP SHORT ${rel8(bytes, offset + 1)}" to 2
            0x74 -> "JE ${rel8(bytes, offset + 1)}" to 2
            0x75 -> "JNE ${rel8(bytes, offset + 1)}" to 2
            0x89 -> "MOV r/m32, r32" to 2
            0x8B -> "MOV r32, r/m32" to 2
            in 0xB8..0xBF -> "MOV ${regs[opcode and 7]}, ${imm32(bytes, offset + 1)}" to 5
            0xC7 -> "MOV r/m32, ${imm32(bytes, offset + 2)}" to 6
            0x83 -> "ADD/SUB r/m32, ${imm8(bytes, offset + 1)}" to 3
            0x81 -> "ADD r/m32, ${imm32(bytes, offset + 2)}" to 6
            0x01 -> "ADD r/m32, r32" to 2
            0x29 -> "SUB r/m32, r32" to 2
            0xFF -> when (((bytes.getOrNull(offset + 1)?.toInt() ?: 0) ushr 3) and 7) {
                0 -> "INC r/m32" to 2
                1 -> "DEC r/m32" to 2
                2 -> "CALL r/m32" to 2
                4 -> "JMP r/m32" to 2
                6 -> "PUSH r/m32" to 2
                else -> "FF /${((bytes.getOrNull(offset + 1)?.toInt() ?: 0) ushr 3) and 7}" to 2
            }
            0x48 -> {
                val op2 = if (offset + 1 < bytes.size) bytes[offset + 1].toInt() and 0xFF else 0
                when (op2) {
                    0x89 -> "MOV r/m64, r64" to 3
                    0x8B -> "MOV r64, r/m64" to 3
                    0x83 -> "ADD/SUB r64, ${imm8(bytes, offset + 2)}" to 4
                    else -> "REX.W" to 1
                }
            }
            else -> String.format("DB 0x%02X", opcode) to 1
        }
    }

    private val regs = arrayOf("RAX", "RCX", "RDX", "RBX", "RSP", "RBP", "RSI", "RDI")

    private fun imm8(b: ByteArray, off: Int) = if (off < b.size) "0x${(b[off].toInt() and 0xFF).toString(16)}" else "?"
    private fun imm16(b: ByteArray, off: Int) = if (off + 1 < b.size) "0x${(b[off].toInt() and 0xFF or ((b[off+1].toInt() and 0xFF) shl 8)).toString(16)}" else "?"
    private fun imm32(b: ByteArray, off: Int) = if (off + 3 < b.size) "0x${read32le(b, off).toString(16)}" else "?"
    private fun rel8(b: ByteArray, off: Int) = if (off < b.size) "0x${(b[off].toInt()).toString(16)}" else "?"
    private fun rel32(b: ByteArray, off: Int) = if (off + 3 < b.size) "0x${read32le(b, off).toString(16)}" else "?"

    private fun read32le(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or
                ((b[off+1].toInt() and 0xFF) shl 8) or
                ((b[off+2].toInt() and 0xFF) shl 16) or
                ((b[off+3].toInt() and 0xFF) shl 24)
    }

    private fun read64le(b: ByteArray, off: Int): Long {
        return (read32le(b, off).toLong() and 0xFFFFFFFFL) or
                ((read32le(b, off + 4).toLong() and 0xFFFFFFFFL) shl 32)
    }
}
