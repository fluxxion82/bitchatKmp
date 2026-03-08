package com.bitchat.lora.bitchat.di

import com.bitchat.lora.bitchat.radio.LoRaRadio
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Linux ARM64 platform module for BitChat LoRa.
 *
 * Provides LoRaRadio using SPI for RFM95W/SX1276 modules.
 *
 * Default configuration for Orange Pi Zero 3:
 * - SPI device: /dev/spidev1.1 (SPI1 with CS1)
 * - Touch screen uses spidev1.0, so LoRa uses spidev1.1
 *
 * For Raspberry Pi, you may need to use /dev/spidev0.0 instead:
 *   single { LoRaRadio("/dev/spidev0.0") }
 */
actual val bitChatLoraPlatformModule: Module = module {
    single { LoRaRadio() }
}
