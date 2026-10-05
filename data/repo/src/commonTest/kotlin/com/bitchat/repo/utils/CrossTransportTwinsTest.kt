package com.bitchat.repo.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CrossTransportTwinsTest {
    private val start = Instant.fromEpochSeconds(0)

    @Test fun bleThenLoRaHidesTheLoRaCopy() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertTrue(twins.onLoRa("lora-1", "alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun loRaThenBleReturnsTheLoRaRowToReplace() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onLoRa("lora-1", "alice", "device-1", "hello", start))
        assertEquals("lora-1", twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun repeatedTextIsPairedOneToOneWhenBleArrivesFirst() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertTrue(twins.onLoRa("lora-1", "alice", "device-1", "hello", start + 1.seconds))
        assertFalse(twins.onLoRa("lora-2", "alice", "device-1", "hello", start + 2.seconds))
    }

    @Test fun repeatedTextIsPairedOneToOneWhenLoRaArrivesFirst() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onLoRa("lora-1", "alice", "device-1", "hello", start))
        assertEquals("lora-1", twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 1.seconds))
        assertFalse(twins.onLoRa("lora-2", "alice", "device-1", "hello", start + 2.seconds))
    }

    @Test fun arrivalsOutsideTheWindowAreNotPaired() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("lora-1", "alice", "device-1", "hello", start + 31.seconds))
    }

    @Test fun arrivalsWithDifferentContentAreNotPaired() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("lora-1", "alice", "device-1", "goodbye", start + 1.seconds))
    }

    @Test fun arrivalsWithDifferentSendersAreNotPaired() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("lora-1", "bob", "device-2", "hello", start + 1.seconds))
    }

    @Test fun twoKnownDevicesSharingANicknameAreNotPaired() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("lora-1", "alice", "device-2", "hello", start + 1.seconds))
    }

    @Test fun aNicknameDecidesOnlyWhenADeviceIdIsMissing() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertTrue(twins.onLoRa("lora-1", "alice", null, "hello", start + 1.seconds))
    }

    @Test fun theDefaultWindowIsTenSeconds() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertNull(twins.onMesh("mesh-2", "alice", "device-1", "again", start))
        assertTrue(twins.onLoRa("lora-1", "alice", "device-1", "hello", start + 10.seconds))
        assertFalse(twins.onLoRa("lora-2", "alice", "device-1", "again", start + 11.seconds))
    }

    @Test fun deviceIdPairsAnUnknownBleNicknameWithALoRaNickname() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "Unknown", "Device-1", "hello", start))
        assertTrue(twins.onLoRa("lora-1", "alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun twoArrivalsOnTheSameTransportAreNeverPaired() {
        val meshTwins = CrossTransportTwins()

        assertNull(meshTwins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertNull(meshTwins.onMesh("mesh-2", "alice", "device-1", "hello", start + 1.seconds))

        val loRaTwins = CrossTransportTwins()
        assertFalse(loRaTwins.onLoRa("lora-1", "alice", "device-1", "hello", start))
        assertFalse(loRaTwins.onLoRa("lora-2", "alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun oldUnpairedArrivalsArePruned() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-old", "alice", "device-1", "hello", start))
        assertNull(twins.onMesh("mesh-current", "alice", "device-1", "different", start + 31.seconds))
        assertFalse(twins.onLoRa("lora-1", "alice", "device-1", "hello", start + 32.seconds))
    }

    @Test fun clearForgetsUnpairedArrivals() {
        val twins = CrossTransportTwins()

        assertNull(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        twins.clear()

        assertFalse(twins.onLoRa("lora-1", "alice", "device-1", "hello", start + 1.seconds))
    }
}
