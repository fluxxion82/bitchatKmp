package com.bitchat.lora.bitchat.di

import com.bitchat.lora.bitchat.radio.LoRaRadio
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Apple platform module for BitChat LoRa (iOS/macOS).
 *
 * Provides a stub LoRaRadio since Apple platforms don't support LoRa hardware.
 */
actual val bitChatLoraPlatformModule: Module = module {
    single { LoRaRadio() }
}
