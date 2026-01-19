package com.bitchat.nostr

import android.content.Context
import java.io.File

class AndroidResourceReader(
    private val context: Context
) : ResourceReader {
    override fun readResourceFile(filename: String): ByteArray? {
        return context.assets.open(filename).use { it.readBytes() }
    }

    override fun readFile(filepath: String): ByteArray? {
        return try {
            val file = File(context.filesDir, filepath)
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
            val file = File(context.filesDir, filepath)
            file.writeBytes(data)
            true
        } catch (e: Exception) {
            false
        }
    }
}
