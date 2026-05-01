package com.hermes.analyzer.utils

import android.util.Log

class NativeBridge {
    companion object {
        private const val TAG = "NativeBridge"
        private var libraryLoaded = false

        init {
            try {
                System.loadLibrary("hermesnative")
                libraryLoaded = true
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library not found: ${e.message}")
                libraryLoaded = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library: ${e.message}")
                libraryLoaded = false
            }
        }
    }

    fun disassembleNative(bytes: ByteArray, baseAddr: Long, arch: String): String {
        if (!libraryLoaded) {
            return "[Native library not available - using fallback]"
        }
        return try {
            disassembleNativeInternal(bytes, baseAddr, arch)
        } catch (e: Exception) {
            "[Native error: ${e.message}]"
        }
    }

    fun getBinaryInfoNative(path: String): String {
        if (!libraryLoaded) {
            return "{\"error\": \"Native library not available\"}"
        }
        return try {
            getBinaryInfoNativeInternal(path)
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }
    }

    private external fun disassembleNativeInternal(bytes: ByteArray, baseAddr: Long, arch: String): String
    private external fun getBinaryInfoNativeInternal(path: String): String
}
