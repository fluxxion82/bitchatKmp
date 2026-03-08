package com.bitchat.embedded.di

import com.bitchat.lora.LoRaProtocolType
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv

/**
 * Reads the preferred LoRa protocol from saved app settings.
 *
 * The protocol is selected by the user in the app's Settings screen
 * and stored in ~/.bitchat/settings/lora_settings.prefs
 */
object LoRaProtocolSelector {

    private const val SETTINGS_KEY = "lora_protocol"

    /**
     * Get the preferred LoRa protocol type from saved settings.
     *
     * @return The selected protocol type, or BITCHAT as default
     */
    @OptIn(ExperimentalForeignApi::class)
    fun getPreferredProtocol(): LoRaProtocolType {
        val settingsFile = getSettingsFilePath()
        if (settingsFile != null && access(settingsFile, F_OK) == 0) {
            val savedProtocol = readSettingsValue(settingsFile, SETTINGS_KEY)?.trim()?.uppercase()
            when (savedProtocol) {
                "MESHTASTIC" -> {
                    println("📡 LoRa protocol: Meshtastic")
                    return LoRaProtocolType.MESHTASTIC
                }
                "MESHCORE" -> {
                    println("📡 LoRa protocol: MeshCore")
                    return LoRaProtocolType.MESHCORE
                }
                "BITCHAT" -> {
                    println("📡 LoRa protocol: BitChat")
                    return LoRaProtocolType.BITCHAT
                }
                else -> {
                    if (savedProtocol != null) {
                        println("⚠️ Unknown protocol in settings: $savedProtocol, using BitChat")
                    }
                }
            }
        }

        // Default to BitChat
        println("📡 LoRa protocol: BitChat (default)")
        return LoRaProtocolType.BITCHAT
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getSettingsFilePath(): String? {
        val home = getenv("HOME")?.toKString() ?: return null
        return "$home/.bitchat/settings/lora_settings.prefs"
    }

    /**
     * Read a value from a key=value settings file.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun readSettingsValue(path: String, key: String): String? {
        val file = fopen(path, "r") ?: return null
        return try {
            memScoped {
                val buffer = allocArray<ByteVar>(4096)
                while (fgets(buffer, 4096, file) != null) {
                    val line = buffer.toKString().trim()
                    if (line.startsWith("$key=")) {
                        return line.substring(key.length + 1)
                    }
                }
                null
            }
        } finally {
            fclose(file)
        }
    }
}
