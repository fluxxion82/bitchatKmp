package com.bitchat.lora.bitchat

import com.bitchat.lora.bitchat.protocol.RangePiBeacon
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BitChatLoRaProtocolProbeTest {

    @Test
    fun `probe enabled classifies valid beacon ahead of frame parsing`() {
        val beacon = RangePiBeacon.format(
            sequence = 3u,
            mode = RangePiBeacon.Mode.BEACON,
            channel = 18,
            frequencyHz = 868_125_000
        ).encodeToByteArray()

        val parsed = BitChatLoRaProtocol.classifyIncomingData(
            data = beacon,
            beaconProbeEnabled = true
        )

        assertIs<BitChatLoRaProtocol.IncomingClassification.Beacon>(parsed)
        assertEquals(3u, parsed.beacon.sequence)
    }

    @Test
    fun `probe disabled does not classify beacon as beacon`() {
        val beacon = RangePiBeacon.format(
            sequence = 9u,
            mode = RangePiBeacon.Mode.BRIDGE,
            channel = 65,
            frequencyHz = 915_125_000
        ).encodeToByteArray()

        val parsed = BitChatLoRaProtocol.classifyIncomingData(
            data = beacon,
            beaconProbeEnabled = false
        )

        assertIs<BitChatLoRaProtocol.IncomingClassification.Unparsed>(parsed)
    }
}
