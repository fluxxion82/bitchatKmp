package com.bitchat.lora.bitchat.di

import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.bitchat.BitChatLoRaProtocol
import com.bitchat.lora.bitchat.protocol.LoRaAssembler
import com.bitchat.lora.bitchat.protocol.LoRaFragmenter
import com.bitchat.lora.di.LoRaQualifiers
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Platform-specific Koin module for BitChat LoRa components.
 *
 * Provides the LoRaRadio implementation based on platform hardware.
 */
expect val bitChatLoraPlatformModule: Module

/**
 * Common Koin module for BitChat LoRa transport.
 *
 * Provides:
 * - BitChatLoRaProtocol: The concrete BitChat LoRa protocol implementation
 * - LoRaProtocol (named "bitchat_lora"): For use with LoRaProtocolManager
 *
 * Usage:
 * ```
 * startKoin {
 *     modules(bitChatLoraModule)
 * }
 *
 * // Get the transport (concrete type)
 * val transport: BitChatLoRaProtocol = get()
 *
 * // Or get via named qualifier (for protocol manager)
 * val protocol: LoRaProtocol = get(LoRaQualifiers.BitChat)
 * ```
 */
val bitChatLoraModule = module {
    includes(bitChatLoraPlatformModule)

    // Protocol layer - platform independent
    single { LoRaFragmenter() }
    single { LoRaAssembler() }

    // Transport layer - concrete type
    single {
        val probeEnabled = isBeaconProbeEnabled()
        if (probeEnabled) {
            println("📡 BitChat LoRa beacon probe enabled via BITCHAT_LORA_PROBE")
        }
        BitChatLoRaProtocol(
            radio = get(),
            fragmenter = get(),
            assembler = get(),
            beaconProbeEnabled = probeEnabled
        )
    }

    // Named binding for protocol manager
    single<LoRaProtocol>(LoRaQualifiers.BitChat) { get<BitChatLoRaProtocol>() }
}
