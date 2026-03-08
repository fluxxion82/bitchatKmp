package com.bitchat.nostr

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkdir

/**
 * Linux implementation of ResourceReader using POSIX file APIs.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxResourceReader : ResourceReader {

    private val homeDir: String by lazy {
        getenv("HOME")?.toKString() ?: "/tmp"
    }

    private val dataDir: String by lazy {
        val dir = "$homeDir/.bitchat/data"
        mkdir(dir, 0x1C0u) // 0700
        dir
    }

    override fun readResourceFile(filename: String): ByteArray? {
        // On Linux, resources would typically be in /usr/share or bundled with the app
        // For embedded, try reading from data directory
        return readFile(filename)
    }

    override fun readFile(filepath: String): ByteArray? {
        val fullPath = if (filepath.startsWith("/")) filepath else "$dataDir/$filepath"
        val file = fopen(fullPath, "rb") ?: return null

        try {
            // Get file size
            fseek(file, 0, SEEK_END)
            val size = ftell(file).toInt()
            fseek(file, 0, SEEK_SET)

            if (size <= 0) return null

            // Read file contents
            val buffer = ByteArray(size)
            val bytesRead = fread(buffer.refTo(0), 1u, size.toULong(), file)

            return if (bytesRead.toInt() == size) buffer else null
        } finally {
            fclose(file)
        }
    }

    override fun writeFile(data: ByteArray, filepath: String): Boolean {
        val fullPath = if (filepath.startsWith("/")) filepath else "$dataDir/$filepath"

        // Ensure parent directory exists
        val parentDir = fullPath.substringBeforeLast("/")
        if (parentDir != fullPath) {
            mkdir(parentDir, 0x1C0u) // 0700
        }

        val file = fopen(fullPath, "wb") ?: return false

        try {
            val bytesWritten = fwrite(data.refTo(0), 1u, data.size.toULong(), file)
            return bytesWritten.toInt() == data.size
        } finally {
            fclose(file)
        }
    }
}
