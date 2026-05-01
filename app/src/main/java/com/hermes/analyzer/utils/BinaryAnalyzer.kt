package com.hermes.analyzer.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.min

/**
 * Binary file analyzer for APK files.
 * Extracts strings, analyzes headers, and identifies file types.
 */
object BinaryAnalyzer {

    private const val TAG = "BinaryAnalyzer"
    private const val MAX_STRING_LENGTH = 100
    private const val MIN_STRING_LENGTH = 4
    private const val READ_BUFFER_SIZE = 8192

    /**
     * Result of binary analysis
     */
    data class AnalysisResult(
        val fileType: FileType,
        val fileSize: Long,
        val md5Hash: String,
        val sha256Hash: String,
        val strings: List<StringInfo>,
        val headerInfo: HeaderInfo,
        val entropy: Double,
        val sections: List<BinarySection>
    )

    /**
     * Information about a found string
     */
    data class StringInfo(
        val text: String,
        val offset: Long,
        val length: Int,
        val isAscii: Boolean
    )

    /**
     * File type classification
     */
    enum class FileType {
        APK,
        DEX,
        ELF,
        UNKNOWN
    }

    /**
     * Header information
     */
    data class HeaderInfo(
        val magic: String,
        val version: String,
        val architecture: String,
        val flags: List<String>
    )

    /**
     * Binary section information
     */
    data class BinarySection(
        val name: String,
        val offset: Long,
        val size: Long
    )

    /**
     * Analyze a file from a URI
     */
    fun analyzeFile(context: Context, uri: Uri): AnalysisResult? {
        return try {
            val documentFile = DocumentFile.fromSingleUri(context, uri)
            val fileName = documentFile?.name ?: return null
            val tempFile = File(context.cacheDir, "analyze_${System.currentTimeMillis()}_$fileName")
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val result = analyzeFileInternal(tempFile)
            tempFile.delete()
            result
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Analyze a local file
     */
    fun analyzeFile(file: File): AnalysisResult? {
        return analyzeFileInternal(file)
    }

    private fun analyzeFileInternal(file: File): AnalysisResult? {
        return try {
            val bytes = file.readBytes()
            val fileType = detectFileType(bytes)
            val md5Hash = computeMd5(bytes)
            val sha256Hash = computeSha256(bytes)
            val strings = extractStrings(bytes)
            val stringsDetailed = extractStringsDetailed(bytes)
            val headerInfo = parseHeader(bytes, fileType)
            val entropy = calculateEntropy(bytes)
            val sections = parseSections(bytes, fileType)
            
            AnalysisResult(
                fileType = fileType,
                fileSize = file.length(),
                md5Hash = md5Hash,
                sha256Hash = sha256Hash,
                strings = stringsDetailed,
                headerInfo = headerInfo,
                entropy = entropy,
                sections = sections
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract ASCII strings from binary data
     */
    fun extractStrings(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val currentString = StringBuilder()
        var startOffset = 0L

        for ((index, b) in bytes.withIndex()) {
            if (b.toInt() in 0x20..0x7E) {
                if (currentString.length == 0) {
                    startOffset = index.toLong()
                }
                currentString.append(b.toInt().toChar())
                if (currentString.length >= MAX_STRING_LENGTH) {
                    strings.add(currentString.toString())
                    currentString.clear()
                }
            } else {
                if (currentString.length >= MIN_STRING_LENGTH) {
                    strings.add(currentString.toString())
                }
                currentString.clear()
            }
        }
        
        if (currentString.length >= MIN_STRING_LENGTH) {
            strings.add(currentString.toString())
        }

        return strings
    }

    /**
     * Extract ASCII strings with detailed information
     */
    fun extractStringsDetailed(bytes: ByteArray): List<StringInfo> {
        val strings = mutableListOf<StringInfo>()
        val currentString = StringBuilder()
        var startOffset = 0L

        for ((index, b) in bytes.withIndex()) {
            if (b.toInt() in 0x20..0x7E) {
                if (currentString.length == 0) {
                    startOffset = index.toLong()
                }
                currentString.append(b.toInt().toChar())
                if (currentString.length >= MAX_STRING_LENGTH) {
                    strings.add(
                        StringInfo(
                            text = currentString.toString(),
                            offset = startOffset,
                            length = currentString.length,
                            isAscii = true
                        )
                    )
                    currentString.clear()
                }
            } else {
                if (currentString.length >= MIN_STRING_LENGTH) {
                    strings.add(
                        StringInfo(
                            text = currentString.toString(),
                            offset = startOffset,
                            length = currentString.length,
                            isAscii = true
                        )
                    )
                }
                currentString.clear()
            }
        }

        if (currentString.length >= MIN_STRING_LENGTH) {
            strings.add(
                StringInfo(
                    text = currentString.toString(),
                    offset = startOffset,
                    length = currentString.length,
                    isAscii = true
                )
            )
        }

        return strings
    }

    /**
     * Detect file type from magic bytes
     */
    fun detectFileType(bytes: ByteArray): FileType {
        if (bytes.size < 4) return FileType.UNKNOWN

        val header = bytes.copyOf(min(8, bytes.size))

        // Check for ZIP (APK is a ZIP file)
        if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) {
            return FileType.APK
        }

        // Check for DEX
        if (bytes.size >= 8) {
            val isDex = header[0] == 'd'.code.toByte() && header[1] == 'e'.code.toByte() &&
                    header[2] == 'x'.code.toByte() && header[3] == '\n'.code.toByte()
            
            if (isDex) {
                val versionStr = "${header[4].toInt().toChar()}${header[5].toInt().toChar()}${header[6].toInt().toChar()}"
                if (versionStr.all { it.isDigit() }) {
                    return FileType.DEX
                }
            }
        }

        // Check for ELF
        if (header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
            return FileType.ELF
        }

        return FileType.UNKNOWN
    }

    /**
     * Parse file header based on file type
     */
    private fun parseHeader(bytes: ByteArray, fileType: FileType): HeaderInfo {
        return when (fileType) {
            FileType.APK -> {
                val comment = extractZipComment(bytes)
                HeaderInfo(
                    magic = "PK\\x03\\x04 (ZIP/APK)",
                    version = "2.0",
                    architecture = "multi",
                    flags = listOf("compressed", "archive", comment)
                )
            }
            FileType.DEX -> {
                val versionBytes = bytes.copyOfRange(4, 8)
                val versionStr = String(versionBytes, StandardCharsets.US_ASCII)
                HeaderInfo(
                    magic = "dex\\n",
                    version = versionStr,
                    architecture = getDexArchitecture(bytes),
                    flags = listOf("dalvik", "android")
                )
            }
            FileType.ELF -> {
                val arch = when (bytes[4].toInt()) {
                    1 -> "32-bit"
                    2 -> "64-bit"
                    else -> "unknown"
                }
                val endian = when (bytes[5].toInt()) {
                    1 -> "little-endian"
                    2 -> "big-endian"
                    else -> "unknown"
                }
                HeaderInfo(
                    magic = "\\x7FELF",
                    version = "1",
                    architecture = arch,
                    flags = listOf(endian, "executable")
                )
            }
            FileType.UNKNOWN -> {
                HeaderInfo(
                    magic = bytes.take(4).joinToString(" ") { "%02X".format(it) },
                    version = "unknown",
                    architecture = "unknown",
                    flags = emptyList()
                )
            }
        }
    }

    /**
     * Extract ZIP file comment
     */
    private fun extractZipComment(bytes: ByteArray): String {
        if (bytes.size < 22) return ""
        
        // Find end of central directory record
        for (i in bytes.size - 22 downTo 0) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()) {
                
                val commentLength = ByteBuffer.wrap(bytes, i + 20, 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .short.toInt()
                
                if (commentLength > 0 && i + 22 + commentLength <= bytes.size) {
                    return String(bytes, i + 22, commentLength, StandardCharsets.UTF_8)
                }
            }
        }
        
        return ""
    }

    /**
     * Get DEX file target architecture
     */
    private fun getDexArchitecture(bytes: ByteArray): String {
        return try {
            val endianTag = ByteBuffer.wrap(bytes, 40, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
            
            if (endianTag == 0x12345678) {
                "little-endian"
            } else {
                "big-endian"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Parse binary sections
     */
    private fun parseSections(bytes: ByteArray, fileType: FileType): List<BinarySection> {
        val sections = mutableListOf<BinarySection>()

        when (fileType) {
            FileType.APK -> {
                sections.addAll(parseZipSections(bytes))
            }
            FileType.DEX -> {
                sections.addAll(parseDexSections(bytes))
            }
            FileType.ELF -> {
                sections.addAll(parseElfSections(bytes))
            }
            else -> {
                sections.add(BinarySection("raw", 0, bytes.size.toLong()))
            }
        }

        return sections
    }

    /**
     * Parse ZIP/APK sections
     */
    private fun parseZipSections(bytes: ByteArray): List<BinarySection> {
        val sections = mutableListOf<BinarySection>()
        sections.add(BinarySection("local_file_header", 0, 30))
        
        // Find central directory
        var offset = 0L
        while (offset < bytes.size - 4) {
            if (bytes[offset.toInt()] == 0x50.toByte() && 
                bytes[offset.toInt() + 1] == 0x4B.toByte()) {
                
                when {
                    bytes[offset.toInt() + 2] == 0x01.toByte() && 
                    bytes[offset.toInt() + 3] == 0x02.toByte() -> {
                        sections.add(BinarySection("central_directory", offset, 46))
                    }
                    bytes[offset.toInt() + 2] == 0x05.toByte() && 
                    bytes[offset.toInt() + 3] == 0x06.toByte() -> {
                        sections.add(BinarySection("end_central_dir", offset, 22))
                    }
                }
            }
            offset++
        }

        return sections
    }

    /**
     * Parse DEX sections
     */
    private fun parseDexSections(bytes: ByteArray): List<BinarySection> {
        val sections = mutableListOf<BinarySection>()
        
        if (bytes.size < 112) return sections

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Skip magic (8 bytes) and checksum (4 bytes) + signature (20 bytes)
        buffer.position(32)

        val fileSize = buffer.int
        val headerSize = buffer.int
        val endianTag = buffer.int

        sections.add(BinarySection("header", 0, headerSize.toLong()))

        // Parse map list offset
        buffer.position(112)
        val mapOff = buffer.int

        // String IDs
        val stringIdsSize = buffer.int
        val stringIdsOff = buffer.int
        if (stringIdsSize > 0) {
            sections.add(BinarySection("string_ids", stringIdsOff.toLong(), (stringIdsSize * 4).toLong()))
        }

        // Type IDs
        val typeIdsSize = buffer.int
        val typeIdsOff = buffer.int
        if (typeIdsSize > 0) {
            sections.add(BinarySection("type_ids", typeIdsOff.toLong(), (typeIdsSize * 4).toLong()))
        }

        // Proto IDs
        val protoIdsSize = buffer.int
        val protoIdsOff = buffer.int
        if (protoIdsSize > 0) {
            sections.add(BinarySection("proto_ids", protoIdsOff.toLong(), (protoIdsSize * 12).toLong()))
        }

        // Field IDs
        val fieldIdsSize = buffer.int
        val fieldIdsOff = buffer.int
        if (fieldIdsSize > 0) {
            sections.add(BinarySection("field_ids", fieldIdsOff.toLong(), (fieldIdsSize * 8).toLong()))
        }

        // Method IDs
        val methodIdsSize = buffer.int
        val methodIdsOff = buffer.int
        if (methodIdsSize > 0) {
            sections.add(BinarySection("method_ids", methodIdsOff.toLong(), (methodIdsSize * 8).toLong()))
        }

        // Class defs
        val classDefsSize = buffer.int
        val classDefsOff = buffer.int
        if (classDefsSize > 0) {
            sections.add(BinarySection("class_defs", classDefsOff.toLong(), (classDefsSize * 32).toLong()))
        }

        // Data section
        val dataSize = buffer.int
        val dataOff = buffer.int
        if (dataSize > 0) {
            sections.add(BinarySection("data", dataOff.toLong(), dataSize.toLong()))
        }

        return sections
    }

    /**
     * Parse ELF sections
     */
    private fun parseElfSections(bytes: ByteArray): List<BinarySection> {
        val sections = mutableListOf<BinarySection>()
        
        if (bytes.size < 52) return sections

        val is64Bit = bytes[4].toInt() == 2
        val littleEndian = bytes[5].toInt() == 1

        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        try {
            val buffer = ByteBuffer.wrap(bytes).order(order)

            if (is64Bit) {
                // 64-bit ELF
                if (bytes.size < 64) return sections

                buffer.position(40)
                val eShoff = buffer.long  // Section header offset
                val eShentsize = buffer.short  // Section header entry size
                buffer.getShort()  // eShnum
                val eShstrndx = buffer.short  // Section name string table index

                sections.add(BinarySection("elf_header", 0, 64))

                if (eShoff > 0 && eShoff < bytes.size) {
                    sections.add(BinarySection("section_headers", eShoff, eShentsize.toLong()))
                }
            } else {
                // 32-bit ELF
                buffer.position(28)
                val eShoff = buffer.int  // Section header offset
                val eShentsize = buffer.short  // Section header entry size
                buffer.getShort()  // eShnum
                val eShstrndx = buffer.short  // Section name string table index

                sections.add(BinarySection("elf_header", 0, 52))

                if (eShoff > 0 && eShoff < bytes.size) {
                    sections.add(BinarySection("section_headers", eShoff.toLong(), eShentsize.toLong()))
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }

        return sections
    }

    /**
     * Calculate Shannon entropy of the file
     */
    fun calculateEntropy(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0

        val counts = IntArray(256)
        for (b in bytes) {
            counts[b.toInt() and 0xFF]++
        }

        var entropy = 0.0
        val length = bytes.size.toDouble()

        for (count in counts) {
            if (count > 0) {
                val probability = count / length
                entropy -= probability * kotlin.math.log2(probability)
            }
        }

        return entropy
    }

    /**
     * Compute MD5 hash of bytes
     */
    private fun computeMd5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute SHA-256 hash of bytes
     */
    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Read a file's bytes safely
     */
    fun readBytes(file: File): ByteArray? {
        return try {
            file.readBytes()
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Format bytes to human-readable string
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * Check if a byte array contains a specific pattern
     */
    fun containsPattern(bytes: ByteArray, pattern: ByteArray, startOffset: Int = 0): Boolean {
        if (pattern.isEmpty() || bytes.size < pattern.size) return false

        for (i in startOffset..bytes.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (bytes[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }

        return false
    }

    /**
     * Find all occurrences of a pattern in bytes
     */
    fun findPatternOffsets(bytes: ByteArray, pattern: ByteArray): List<Long> {
        val offsets = mutableListOf<Long>()
        if (pattern.isEmpty() || bytes.size < pattern.size) return offsets

        for (i in 0..bytes.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (bytes[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) offsets.add(i.toLong())
        }

        return offsets
    }

    /**
     * Get null-terminated string from byte array at given offset
     */
    fun getNullTerminatedString(bytes: ByteArray, offset: Int, maxLength: Int = 256): String {
        val length = min(maxLength, bytes.size - offset)
        val result = StringBuilder()
        
        for (i in 0 until length) {
            val b = bytes[offset + i]
            if (b == 0.toByte()) break
            if (b.toInt() in 0x20..0x7E) {
                result.append(b.toInt().toChar())
            } else {
                result.append('.')
            }
        }
        
        return result.toString()
    }

    /**
     * Read a little-endian 32-bit integer from bytes
     */
    fun readUInt32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    /**
     * Read a little-endian 16-bit integer from bytes
     */
    fun readUInt16(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Check if byte represents a printable ASCII character
     */
    fun isPrintableAscii(b: Byte): Boolean {
        return b.toInt() in 0x20..0x7E
    }

    /**
     * Convert bytes to hex string representation
     */
    fun toHexString(bytes: ByteArray, maxLength: Int = 64): String {
        val length = min(bytes.size, maxLength)
        val hex = bytes.take(length).joinToString(" ") { "%02X".format(it) }
        return if (bytes.size > maxLength) "$hex ..." else hex
    }

    /**
     * Analyze ELF file for architecture details
     */
    fun analyzeElfArchitecture(bytes: ByteArray): String {
        if (bytes.size < 20) return "Invalid ELF file"
        if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
            return "Not an ELF file"
        }

        val arch = when (bytes[4].toInt()) {
            1 -> "32-bit"
            2 -> "64-bit"
            else -> "Unknown class"
        }

        val endian = when (bytes[5].toInt()) {
            1 -> "Little Endian"
            2 -> "Big Endian"
            else -> "Unknown endianness"
        }

        val machine = if (bytes.size >= 19) {
            val machineType = (bytes[18].toInt() and 0xFF) or
                    ((bytes[19].toInt() and 0xFF) shl 8)
            when (machineType) {
                0x00 -> "No specific"
                0x02 -> "SPARC"
                0x03 -> "x86"
                0x08 -> "MIPS"
                0x14 -> "PowerPC"
                0x28 -> "ARM"
                0x32 -> "IA-64"
                0x3E -> "x86-64"
                0xB7 -> "AArch64"
                else -> "Unknown (0x%04X)".format(machineType)
            }
        } else {
            "Unknown"
        }

        return "$arch, $endian, $machine"
    }

    /**
     * Parse string table from DEX file
     */
    fun parseDexStringTable(bytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        
        if (bytes.size < 112) return strings
        if (!(bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() &&
              bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte())) {
            return strings
        }

        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(56)
            
            val stringIdsSize = buffer.int
            val stringIdsOff = buffer.int
            
            if (stringIdsOff == 0 || stringIdsSize == 0) return strings

            val stringOffsets = IntArray(stringIdsSize)
            buffer.position(stringIdsOff)
            
            for (i in 0 until stringIdsSize) {
                stringOffsets[i] = buffer.int
            }

            for (offset in stringOffsets) {
                if (offset > 0 && offset < bytes.size) {
                    val str = readMutf8String(bytes, offset)
                    if (str.isNotEmpty()) {
                        strings.add(str)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }

        return strings
    }

    /**
     * Read a MUTF-8 string from DEX file
     */
    private fun readMutf8String(bytes: ByteArray, offset: Int): String {
        val result = StringBuilder()
        var i = offset

        // Read ULEB128 length (simplified - just read until null)
        while (i < bytes.size && bytes[i] != 0.toByte()) {
            i++
        }
        
        val length = i - offset
        if (length <= 0) return ""

        // Simple ASCII extraction (MUTF-8 is mostly ASCII-compatible for common strings)
        for (j in 0 until min(length, 256)) {
            val b = bytes[offset + j]
            if (b == 0.toByte()) break
            if (b.toInt() in 0x20..0x7E) {
                result.append(b.toInt().toChar())
            } else {
                result.append('.')
            }
        }

        return result.toString()
    }

    /**
     * Get a summary of the binary file
     */
    fun getBinarySummary(bytes: ByteArray): String {
        val fileType = detectFileType(bytes)
        val entropy = calculateEntropy(bytes)
        val stringCount = extractStrings(bytes).size

        return buildString {
            appendLine("File Type: $fileType")
            appendLine("Size: ${formatBytes(bytes.size.toLong())}")
            appendLine("Entropy: ${"%.2f".format(entropy)} bits/byte")
            appendLine("Strings found: $stringCount")
            appendLine("Magic: ${toHexString(bytes.copyOf(8), 8)}")
            
            when (fileType) {
                FileType.ELF -> {
                    appendLine("Architecture: ${analyzeElfArchitecture(bytes)}")
                }
                FileType.DEX -> {
                    val strings = parseDexStringTable(bytes)
                    appendLine("DEX strings: ${strings.size}")
                }
                else -> {}
            }
        }
    }

    /**
     * Check if file is an APK by looking for AndroidManifest.xml
     */
    fun isApk(bytes: ByteArray): Boolean {
        // Check for ZIP magic
        if (bytes.size < 4) return false
        if (!(bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
              bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte())) {
            return false
        }

        // Look for AndroidManifest.xml entry in the ZIP central directory
        val manifestPattern = "AndroidManifest.xml".toByteArray(StandardCharsets.US_ASCII)
        return containsPattern(bytes, manifestPattern)
    }
    /**
     * Extract features from file for AI analysis
     */
    fun extractFeatures(filePath: String, fileType: String): Map<String, Any> {
        val file = File(filePath)
        val bytes = readBytes(file) ?: return mapOf("error" to "Cannot read file")
        val features = mutableMapOf<String, Any>()

        features["fileSize"] = file.length()
        features["fileType"] = fileType
        features["entropy"] = calculateEntropy(bytes)
        features["md5"] = computeHash(bytes, "MD5")
        features["sha256"] = computeHash(bytes, "SHA-256")
        features["strings"] = extractStrings(bytes).take(100)
        features["hexPreview"] = toHexString(bytes, 128)

        when (detectFileType(bytes)) {
            FileType.ELF -> {
                features["architecture"] = analyzeElfArchitecture(bytes)
                features["type"] = "ELF Binary"
            }
            FileType.DEX -> {
                features["type"] = "DEX File"
                features["stringCount"] = parseDexStringTable(bytes).size
            }
            FileType.APK -> {
                features["type"] = "APK Archive"
                features["isApk"] = true
            }
            else -> features["type"] = "Unknown"
        }

        return features
    }

    private fun computeHash(bytes: ByteArray, algorithm: String): String {
        val md = java.security.MessageDigest.getInstance(algorithm)
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

