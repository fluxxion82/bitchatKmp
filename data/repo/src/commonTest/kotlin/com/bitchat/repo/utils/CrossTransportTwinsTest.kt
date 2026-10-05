package com.bitchat.repo.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CrossTransportTwinsTest {
    private val start = Instant.fromEpochSeconds(0)

    @Test fun bleThenLoRaDropsTheLoRaCopy() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertTrue(twins.onLoRa("alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun loRaThenBleDropsTheBleCopy() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onLoRa("alice", "device-1", "hello", start))
        assertTrue(twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun aRelayedRepeatOfADroppedBleCopyIsDroppedToo() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onLoRa("alice", "device-1", "hello", start))
        assertTrue(twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 1.seconds))
        assertTrue(twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 2.seconds))
    }

    @Test fun repeatedTextIsPairedOneToOneWhenBleArrivesFirst() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertTrue(twins.onLoRa("alice", "device-1", "hello", start + 1.seconds))
        assertFalse(twins.onLoRa("alice", "device-1", "hello", start + 2.seconds))
    }

    @Test fun repeatedTextIsPairedOneToOneWhenLoRaArrivesFirst() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onLoRa("alice", "device-1", "hello", start))
        assertTrue(twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 1.seconds))
        assertFalse(twins.onMesh("mesh-2", "alice", "device-1", "hello", start + 2.seconds))
    }

    @Test fun arrivalsOutsideTheWindowAreNotPaired() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("alice", "device-1", "hello", start + 11.seconds))
    }

    @Test fun theDefaultWindowIsTenSeconds() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onMesh("mesh-2", "alice", "device-1", "again", start))
        assertTrue(twins.onLoRa("alice", "device-1", "hello", start + 10.seconds))
        assertFalse(twins.onLoRa("alice", "device-1", "again", start + 11.seconds))
    }

    @Test fun arrivalsWithDifferentContentAreNotPaired() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("alice", "device-1", "goodbye", start + 1.seconds))
    }

    @Test fun arrivalsWithDifferentSendersAreNotPaired() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("bob", "device-2", "hello", start + 1.seconds))
    }

    @Test fun twoKnownDevicesSharingANicknameAreNotPaired() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("alice", "device-2", "hello", start + 1.seconds))
    }

    @Test fun aNicknameDecidesOnlyWhenADeviceIdIsMissing() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertTrue(twins.onLoRa("alice", null, "hello", start + 1.seconds))
    }

    @Test fun deviceIdPairsAnUnknownBleNicknameWithALoRaNickname() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "Unknown", "Device-1", "hello", start))
        assertTrue(twins.onLoRa("alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun twoArrivalsOnTheSameTransportAreNeverPaired() {
        val meshTwins = CrossTransportTwins()

        assertFalse(meshTwins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(meshTwins.onMesh("mesh-2", "alice", "device-1", "hello", start + 1.seconds))

        val loRaTwins = CrossTransportTwins()
        assertFalse(loRaTwins.onLoRa("alice", "device-1", "hello", start))
        assertFalse(loRaTwins.onLoRa("alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun oldUnpairedArrivalsArePruned() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-old", "alice", "device-1", "hello", start))
        assertFalse(twins.onMesh("mesh-current", "alice", "device-1", "different", start + 11.seconds))
        assertFalse(twins.onLoRa("alice", "device-1", "hello", start + 12.seconds))
    }

    @Test fun clearForgetsEveryArrival() {
        val twins = CrossTransportTwins()

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))
        assertFalse(twins.onLoRa("bob", "device-2", "hi", start))
        assertTrue(twins.onMesh("mesh-2", "bob", "device-2", "hi", start + 1.seconds))
        twins.clear()

        assertFalse(twins.onLoRa("alice", "device-1", "hello", start + 2.seconds))
        assertFalse(twins.onMesh("mesh-2", "bob", "device-2", "hi", start + 2.seconds))
    }
}
