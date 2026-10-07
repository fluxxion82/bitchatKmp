package com.bitchat.lora.radio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoRaAirtimeTest {
    @Test fun defaultConfigurationCalculatesAirtimeForShortFrame() {
        val config = LoRaConfig()

        assertEquals(205_824L, config.airtimeMicros(22))
        assertEquals(206L, config.airtimeMs(22))
    }

    @Test fun defaultConfigurationCalculatesAirtimeForMaximumFrame() {
        assertEquals(1_168_384L, LoRaConfig().airtimeMicros(237))
        assertEquals(1_169L, LoRaConfig().airtimeMs(237))
    }

    @Test fun widerBandwidthReducesAirtimeByFour() {
        val config = LoRaConfig(bandwidth = 500_000L)

        assertEquals(51_456L, config.airtimeMicros(22))
        assertEquals(292_096L, config.airtimeMicros(237))
    }

    @Test fun emptyFrameUsesTheFormulaPayloadSymbols() {
        // (0 - 36 + 28 + 16) / 36 rounds up to 1, so 13 payload symbols make 25.25 total symbols.
        assertEquals(103_424L, LoRaConfig().airtimeMicros(0))
    }

    @Test fun negativeFrameLengthIsRejected() {
        assertFailsWith<IllegalArgumentException> { LoRaConfig().airtimeMicros(-1) }
    }

    @Test fun airtimeNeverDecreasesAsTheFrameGrows() {
        for (spreadingFactor in listOf(7, 9, 12)) {
            val config = LoRaConfig(spreadingFactor = spreadingFactor)

            for (frameBytes in 1..237) {
                assertTrue(
                    config.airtimeMicros(frameBytes) >= config.airtimeMicros(frameBytes - 1),
                    "SF$spreadingFactor decreased at $frameBytes bytes"
                )
            }
        }
    }

    @Test fun symbolTimeScaleMatchesSf9At125KhzBaseline() {
        assertEquals(1.0, LoRaConfig().symbolTimeScale())
        assertEquals(0.25, LoRaConfig(bandwidth = 500_000L).symbolTimeScale())
        assertEquals(0.5, LoRaConfig(bandwidth = 250_000L).symbolTimeScale())
        assertEquals(8.0, LoRaConfig(spreadingFactor = 12).symbolTimeScale())
        assertEquals(0.25, LoRaConfig(spreadingFactor = 7).symbolTimeScale())
    }

    @Test fun sf12AirtimeUsesTheDriversLowDataRateOptimizationRule() {
        // At 125 kHz, DE is 1: 172 / 40 rounds up to 5, giving 45.25 symbols at 32,768 microseconds.
        assertEquals(1_482_752L, LoRaConfig(spreadingFactor = 12).airtimeMicros(22))
        // At 500 kHz, DE is 0: 172 / 48 rounds up to 4, giving 40.25 symbols at 8,192 microseconds.
        assertEquals(329_728L, LoRaConfig(spreadingFactor = 12, bandwidth = 500_000L).airtimeMicros(22))
    }

    @Test fun theCrcIsCountedEvenWhenTheConfigurationSaysNone() {
        // The driver sends a CRC whatever the configuration asks for: counting none would charge less
        // than is transmitted (185,344 against 205,824 microseconds for this frame).
        assertEquals(205_824L, LoRaConfig(enableCrc = false).airtimeMicros(22))
        assertEquals(205_824L, LoRaConfig(enableCrc = true).airtimeMicros(22))
    }

    @Test
    fun aHigherCodingRateLengthensTheFrame() {
        // 4/8 puts eight symbols where 4/5 puts five: 8 + 6 * 8 payload symbols for 22 bytes at SF9,
        // (8 + 4.25 + 56) * 4,096 = 279,552 microseconds.
        assertEquals(279_552L, LoRaConfig(codingRate = 8).airtimeMicros(22))
        assertEquals(205_824L, LoRaConfig(codingRate = 5).airtimeMicros(22))
    }
}
