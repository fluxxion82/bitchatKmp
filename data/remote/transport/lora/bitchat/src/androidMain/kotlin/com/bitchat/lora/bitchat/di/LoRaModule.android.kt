package com.bitchat.lora.bitchat.di

import com.bitchat.lora.bitchat.radio.LoRaRadio
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android platform module for BitChat LoRa.
 *
 * Provides LoRaRadio using USB OTG serial via usb-serial-for-android.
 * Note: LoRaRadio uses KoinComponent to inject Context.
 */
actual val bitChatLoraPlatformModule: Module = module {
    single { LoRaRadio() }
}
