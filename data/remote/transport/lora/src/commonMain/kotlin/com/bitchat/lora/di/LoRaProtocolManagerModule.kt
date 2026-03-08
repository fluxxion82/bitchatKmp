package com.bitchat.lora.di

import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.LoRaProtocolManager
import com.bitchat.lora.LoRaProtocolType
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Koin module that provides LoRaProtocolManager for runtime protocol switching.
 *
 * IMPORTANT: This module requires both bitChatLoraModule and meshtasticLoraModule
 * to be included in your app's Koin configuration before this module.
 *
 * Usage:
 * ```
 * startKoin {
 *     modules(
 *         bitChatLoraModule,
 *         meshtasticLoraModule,
 *         loraProtocolManagerModule
 *     )
 * }
 *
 * // Get the manager (implements LoRaProtocol)
 * val protocol: LoRaProtocol = get()
 *
 * // Or get manager directly to switch protocols
 * val manager: LoRaProtocolManager = get()
 * manager.switchProtocol(LoRaProtocolType.MESHTASTIC, config)
 * ```
 *
 * @param initialProtocol The protocol to use initially (default: BITCHAT)
 */
fun loraProtocolManagerModule(initialProtocol: LoRaProtocolType = LoRaProtocolType.BITCHAT) = module {
    single {
        LoRaProtocolManager(
            bitChatProtocol = lazy { get<LoRaProtocol>(LoRaQualifiers.BitChat) },
            meshtasticProtocol = lazy { get<LoRaProtocol>(LoRaQualifiers.Meshtastic) },
            meshcoreProtocol = lazy { get<LoRaProtocol>(LoRaQualifiers.MeshCore) }
        ).apply {
            setActiveType(initialProtocol)
        }
    } bind LoRaProtocol::class
}

/**
 * Default module with BitChat as initial protocol.
 */
val loraProtocolManagerModule = loraProtocolManagerModule(LoRaProtocolType.BITCHAT)
