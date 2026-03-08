package com.bitchat.lora.meshtastic.di

import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.di.LoRaQualifiers
import com.bitchat.lora.meshtastic.MeshtasticProtocol
import com.bitchat.lora.meshtastic.MeshtasticSerial
import org.koin.dsl.module

/**
 * Koin module for Meshtastic protocol dependencies.
 *
 * Provides:
 * - MeshtasticSerial: Platform-specific serial connection
 * - MeshtasticProtocol: The concrete Meshtastic protocol implementation
 * - LoRaProtocol (named "meshtastic_lora"): For use with LoRaProtocolManager
 *
 * Usage:
 * ```
 * startKoin {
 *     modules(meshtasticLoraModule)
 * }
 *
 * // Get the transport (concrete type)
 * val transport: MeshtasticProtocol = get()
 *
 * // Or get via named qualifier (for protocol manager)
 * val protocol: LoRaProtocol = get(LoRaQualifiers.Meshtastic)
 * ```
 */
val meshtasticLoraModule = module {

    /**
     * Meshtastic serial connection.
     *
     * On Android, the context must be set before using:
     * ```
     * get<MeshtasticSerial>().context = applicationContext
     * ```
     */
    single { MeshtasticSerial() }

    /**
     * Meshtastic protocol implementation - concrete type.
     */
    single { MeshtasticProtocol(get()) }

    /**
     * Named binding for protocol manager.
     */
    single<LoRaProtocol>(LoRaQualifiers.Meshtastic) { get<MeshtasticProtocol>() }
}

/**
 * Legacy alias for backward compatibility.
 */
@Deprecated("Use meshtasticLoraModule instead", ReplaceWith("meshtasticLoraModule"))
val meshtasticModule = meshtasticLoraModule
