package com.bitchat.lora.bitchat.di

import com.bitchat.lora.bitchat.radio.LoRaRadio
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * JVM platform module for BitChat LoRa.
 *
 * Provides LoRaRadio using jSerialComm for USB serial dongles.
 */
actual val bitChatLoraPlatformModule: Module = module {
    single { LoRaRadio() }
}
