package com.bitchat.lora.meshcore.di

import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.di.LoRaQualifiers
import com.bitchat.lora.meshcore.MeshCoreProtocol
import com.bitchat.lora.meshcore.MeshCoreSerial
import org.koin.dsl.module

/**
 * Koin module for MeshCore LoRa protocol.
 *
 * Provides:
 * - MeshCoreSerial: TCP connection to meshcore-pi daemon
 * - MeshCoreProtocol: LoRaProtocol implementation using MeshCore
 *
 * Usage:
 * ```
 * startKoin {
 *     modules(
 *         meshcoreLoraModule,
 *         // ... other modules
 *     )
 * }
 *
 * // Get via qualifier
 * val meshcore: LoRaProtocol = get(LoRaQualifiers.MeshCore)
 * ```
 */
val meshcoreLoraModule = module {
    // MeshCore serial connection
    single { MeshCoreSerial() }

    // MeshCore protocol implementation
    single { MeshCoreProtocol(get()) }

    // Bind as LoRaProtocol with MeshCore qualifier
    single<LoRaProtocol>(LoRaQualifiers.MeshCore) { get<MeshCoreProtocol>() }
}
