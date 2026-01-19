package com.bitchat.nostr

import java.io.File

class JvmResourceReader(
    private val baseDirectory: File = File(System.getProperty("user.home"), ".bitchat")
) : ResourceReader {

    override fun readResourceFile(filename: String): ByteArray? {
        return try {
            this::class.java.classLoader
                ?.getResourceAsStream(filename)
                ?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    override fun readFile(filepath: String): ByteArray? {
        return try {
            val file = File(baseDirectory, filepath)
            if (file.exists() && file.canRead()) {
                file.readBytes()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun writeFile(data: ByteArray, filepath: String): Boolean {
        return try {
            val file = File(baseDirectory, filepath)
            file.parentFile?.mkdirs()
            file.writeBytes(data)
            true
        } catch (e: Exception) {
            false
        }
    }
}
