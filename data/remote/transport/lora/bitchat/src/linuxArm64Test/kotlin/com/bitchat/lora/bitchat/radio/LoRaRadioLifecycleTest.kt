package com.bitchat.lora.bitchat.radio

import com.bitchat.lora.radio.LoRaConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoRaRadioLifecycleTest {
    private class FakeSpi : SpiPort {
        val registers = mutableMapOf(0x42 to 0x12)
        var opens = 0
        var closes = 0
        var transfers = 0
        var active = false
        override fun open(device: String): Int { check(!active); active = true; opens++; return 9 }
        override fun configure(fd: Int, speed: Int) = true
        override fun transfer(fd: Int, data: ByteArray): ByteArray {
            check(active) { "Transfer after descriptor close" }
            transfers++
            val register = data[0].toInt() and 0x7f
            if (data[0].toInt() and 0x80 != 0) registers[register] = data[1].toInt() and 0xff
            return byteArrayOf(0, (registers[register] ?: 0).toByte())
        }
        override fun close(fd: Int) { check(active); active = false; closes++ }
    }

    @Test
    fun restartResumesPollingAndShutdownFinishesBeforeDescriptorRelease() = runBlocking {
        val spi = FakeSpi()
        val radio = LoRaRadio(spi = spi, acquireOwnership = { true })
        assertTrue(radio.configure(LoRaConfig.US_915))
        radio.startReceiving()
        delay(65)
        radio.shutdown()
        val stoppedCount = spi.transfers
        delay(65)
        assertEquals(stoppedCount, spi.transfers)
        assertTrue(radio.configure(LoRaConfig.US_915))
        val configuredCount = spi.transfers
        radio.startReceiving()
        delay(65)
        assertTrue(spi.transfers > configuredCount)
        radio.shutdown()
        radio.shutdown()
        assertEquals(2, spi.opens)
        assertEquals(2, spi.closes)
    }

    @Test
    fun failedInitializationReleasesDescriptorAndRepeatedConfigureDoesNotLeak() = runBlocking {
        val spi = FakeSpi()
        val radio = LoRaRadio(spi = spi, acquireOwnership = { true })
        spi.registers[0x42] = 0
        assertFalse(radio.configure(LoRaConfig.US_915))
        assertEquals(1, spi.closes)
        spi.registers[0x42] = 0x12
        assertTrue(radio.configure(LoRaConfig.US_915))
        assertTrue(radio.configure(LoRaConfig.US_915))
        assertEquals(2, spi.opens)
        radio.shutdown()
        assertEquals(2, spi.closes)
    }
}
