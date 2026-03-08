package com.bitchat.local.prefs

import com.russhwolf.settings.Settings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.mkdir

/**
 * Linux implementation of EncryptionSettingsFactory.
 *
 * Uses file-based storage with POSIX file permissions for security.
 * For embedded/headless Linux systems, this provides basic persistence
 * without requiring a keychain or credential store.
 *
 * Note: This is less secure than Apple's Keychain or Windows Credential Store.
 * For production embedded deployments, consider:
 * - Using Linux Secret Service (libsecret) via cinterop
 * - Encrypting preferences file with a device-specific key
 * - Using a hardware security module if available
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxEncryptionSettingsFactory : EncryptionSettingsFactory {

    private val prefsDir: String by lazy {
        val home = getenv("HOME")?.toKString() ?: "/tmp"
        val baseDir = "$home/.bitchat"
        val dir = "$baseDir/prefs"
        // Create directories with restricted permissions (0700)
        mkdir(baseDir, 0x1C0u)
        mkdir(dir, 0x1C0u)
        dir
    }

    // Cache of loaded settings to avoid re-reading files
    private val settingsCache = mutableMapOf<String, LinuxFileSettings>()

    override fun createEncrypted(name: String): Settings {
        return settingsCache.getOrPut(name) {
            LinuxFileSettings("$prefsDir/$name.prefs")
        }
    }
}

/**
 * Simple file-based Settings implementation for Linux.
 * Stores key-value pairs in a simple text file format.
 */
@OptIn(ExperimentalForeignApi::class)
class LinuxFileSettings(private val filepath: String) : Settings {
    private val data = mutableMapOf<String, String>()

    init {
        loadFromFile()
    }

    private fun loadFromFile() {
        val file = fopen(filepath, "r") ?: return

        try {
            val buffer = ByteArray(4096)
            while (fgets(buffer.refTo(0), buffer.size, file) != null) {
                val line = buffer.toKString().trim()
                if (line.isNotEmpty() && line.contains('=')) {
                    val idx = line.indexOf('=')
                    val key = line.substring(0, idx)
                    val value = line.substring(idx + 1)
                    data[key] = value
                }
            }
        } finally {
            fclose(file)
        }
    }

    private fun saveToFile() {
        val file = fopen(filepath, "w") ?: return

        try {
            for ((key, value) in data) {
                val line = "$key=$value\n"
                fputs(line, file)
            }
        } finally {
            fclose(file)
        }
    }

    override val keys: Set<String> get() = data.keys.toSet()
    override val size: Int get() = data.size

    override fun clear() {
        data.clear()
        saveToFile()
    }

    override fun remove(key: String) {
        data.remove(key)
        saveToFile()
    }

    override fun hasKey(key: String): Boolean = data.containsKey(key)

    override fun putInt(key: String, value: Int) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        data[key]?.toIntOrNull() ?: defaultValue

    override fun getIntOrNull(key: String): Int? = data[key]?.toIntOrNull()

    override fun putLong(key: String, value: Long) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        data[key]?.toLongOrNull() ?: defaultValue

    override fun getLongOrNull(key: String): Long? = data[key]?.toLongOrNull()

    override fun putString(key: String, value: String) {
        data[key] = value
        saveToFile()
    }

    override fun getString(key: String, defaultValue: String): String =
        data[key] ?: defaultValue

    override fun getStringOrNull(key: String): String? = data[key]

    override fun putFloat(key: String, value: Float) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getFloat(key: String, defaultValue: Float): Float =
        data[key]?.toFloatOrNull() ?: defaultValue

    override fun getFloatOrNull(key: String): Float? = data[key]?.toFloatOrNull()

    override fun putDouble(key: String, value: Double) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getDouble(key: String, defaultValue: Double): Double =
        data[key]?.toDoubleOrNull() ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? = data[key]?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) {
        data[key] = value.toString()
        saveToFile()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        data[key]?.toBooleanStrictOrNull() ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? = data[key]?.toBooleanStrictOrNull()
}
