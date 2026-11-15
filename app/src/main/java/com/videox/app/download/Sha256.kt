package com.videox.app.download

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** SHA-256 helpers for verifying runtime-downloaded model files. Pure JVM, unit-tested. */
object Sha256 {

    fun hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Hash a file by streaming it (the file is never fully loaded into memory). */
    fun fileHex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
