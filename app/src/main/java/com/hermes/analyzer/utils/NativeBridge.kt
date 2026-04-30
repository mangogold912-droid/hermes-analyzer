package com.hermes.analyzer.utils

class NativeBridge {
    companion object {
        init {
            System.loadLibrary("hermesnative")
        }
    }

    external fun disassembleNative(bytes: ByteArray, baseAddr: Long, arch: String): String
    external fun getBinaryInfoNative(path: String): String
}
