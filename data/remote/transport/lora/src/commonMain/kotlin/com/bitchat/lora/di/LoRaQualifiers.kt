package com.bitchat.lora.di

import org.koin.core.qualifier.named

/**
 * Koin qualifiers for LoRa protocol implementations.
 *
 * Used to distinguish between different protocol implementations
 * when both are loaded for runtime switching.
 */
object LoRaQualifiers {
    /**
     * Qualifier for BitChat LoRa protocol.
     */
    val BitChat = named("bitchat_lora")

    /**
     * Qualifier for Meshtastic LoRa protocol.
     */
    val Meshtastic = named("meshtastic_lora")

    /**
     * Qualifier for MeshCore LoRa protocol.
     */
    val MeshCore = named("meshcore_lora")
}
