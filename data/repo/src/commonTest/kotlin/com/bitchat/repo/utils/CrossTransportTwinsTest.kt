package com.bitchat.repo.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CrossTransportTwinsTest {
    private val start = Instant.fromEpochSeconds(0)

    @Test fun aRowThatIsNoLongerShownDoesNotHideItsTwin() {
        val twins = CrossTransportTwins()
        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start))

        // The channel dropped that row as its oldest: nothing of it is on screen any more.
        twins.forget(listOf("mesh-1"))

        assertFalse(twins.onLoRa("alice", "device-1", "hello", start + 1.seconds), "the LoRa copy is the only one left to show")
    }

    @Test fun aLoRaRowThatIsNoLongerShownDoesNotHideItsTwinEither() {
        val twins = CrossTransportTwins()
        assertFalse(twins.onLoRa("alice", "device-1", "hello", start, rowId = "lora-1"))

        twins.forget(listOf("lora-1"))

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 1.seconds))
    }

    @Test fun aRelayedRepeatIsShownOnceTheLoRaRowItWasDroppedForIsGone() {
        val twins = CrossTransportTwins()
        assertFalse(twins.onLoRa("alice", "device-1", "hello", start, rowId = "lora-1"))
        assertTrue(twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 1.seconds))
        assertTrue(twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 2.seconds), "a repeat stays hidden while the row is shown")

        twins.forget(listOf("lora-1"))

        assertFalse(twins.onMesh("mesh-1", "alice", "device-1", "hello", start + 3.seconds))
    }

    @Test fun forgettingOneRowLeavesTheOthersPaired() {
        val twins = CrossTransportTwins()
        twins.onMesh("mesh-1", "alice", "device-1", "hello", start)
        twins.onMesh("mesh-2", "alice", "device-1", "again", start)

        twins.forget(listOf("mesh-1"))

        assertTrue(twins.onLoRa("alice", "device-1", "again", start + 1.seconds))
    }

    @Test fun loRaArrivalsRememberedInsideTheWindowAreBoundedToo() {
        val twins = CrossTransportTwins()
        repeat(129) { index -> twins.onLoRa("alice", "device-1", "text-$index", start, rowId = "lora-$index") }

        assertFalse(twins.onMesh("mesh-0", "alice", "device-1", "text-0", start + 1.seconds), "the oldest arrival was forgotten")
        assertTrue(twins.onMesh("mesh-128", "alice", "device-1", "text-128", start + 1.seconds), "the newest still pairs")
    }

    @Test fun theTextRememberedInsideTheWindowIsBoundedOnBothSides() {
        // Twenty messages of 60,000 characters are 1,200,000: the four oldest are forgotten on each side.
        fun text(index: Int) = "x".repeat(59_990) + "-$index".padStart(10, '0')
        val mesh = CrossTransportTwins()
        repeat(20) { index -> mesh.onMesh("mesh-$index", "alice", "device-1", text(index), start) }
        assertFalse(mesh.onLoRa("alice", "device-1", text(3), start + 1.seconds))
        assertTrue(mesh.onLoRa("alice", "device-1", text(4), start + 1.seconds))

        val lora = CrossTransportTwins()
        repeat(20) { index -> lora.onLoRa("alice", "device-1", text(index), start, rowId = "lora-$index") }
        assertFalse(lora.onMesh("mesh-3", "alice", "device-1", text(3), start + 1.seconds))
        assertTrue(lora.onMesh("mesh-4", "alice", "device-1", text(4), start + 1.seconds))
    }

    @Test fun theMeshIdsRememberedAsDroppedAreBounded() {
        val twins = CrossTransportTwins()
        repeat(129) { index ->
            twins.onLoRa("alice", "device-1", "text-$index", start, rowId = "lora-$index")
            assertTrue(twins.onMesh("mesh-$index", "alice", "device-1", "text-$index", start))
        }

        // The first id dropped is no longer remembered, so its relayed repeat is shown: a duplicate row.
        assertFalse(twins.onMesh("mesh-0", "alice", "device-1", "text-0", start + 1.seconds))
        assertTrue(twins.onMesh("mesh-128", "alice", "device-1", "text-128", start + 1.seconds))
    }

    @Test fun whatIsRememberedInsideTheWindowIsBounded() {
        val twins = CrossTransportTwins()
        // More arrivals inside one window than are remembered: the oldest is forgotten, which costs a
        // duplicate row for it and nothing else.
        repeat(129) { index -> twins.onMesh("mesh-$index", "alice", "device-1", "text-$index", start) }

        assertFalse(twins.onLoRa("alice", "device-1", "text-0", start + 1.seconds), "the oldest arrival was forgotten")
        assertTrue(twins.onLoRa("alice", "device-1", "text-128", start + 1.seconds), "the newest still pairs")
    }

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
