package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MAX_PROCESSED_MESSAGE_IDS
import com.bitchat.bluetooth.protocol.MAX_RECENT_ANNOUNCEMENTS
import com.bitchat.bluetooth.protocol.MessageType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

class SecurityManagerBoundsTest {
    @Test
    fun nonAnnouncementsHaveAHardCapacityAndOriginalKeyCanBeForgotten() {
        manager().use { fixture ->
            repeat(MAX_PROCESSED_MESSAGE_IDS + 1) { assertTrue(fixture.manager.validatePacket(packet(MessageType.MESSAGE, it), "peer")) }
            assertEquals(MAX_PROCESSED_MESSAGE_IDS, fixture.manager.processedMessageCount)
            assertTrue(fixture.manager.validatePacket(packet(MessageType.MESSAGE, 0), "peer"))
            assertFalse(fixture.manager.validatePacket(packet(MessageType.MESSAGE, MAX_PROCESSED_MESSAGE_IDS), "peer"))
        }
    }

    @Test
    fun nonAnnouncementDuplicateExpiresAfterFiveMinutes() {
        manager().use { fixture ->
            assertTrue(fixture.manager.validatePacket(packet(MessageType.MESSAGE, 1), "peer"))
            assertFalse(fixture.manager.validatePacket(packet(MessageType.MESSAGE, 1), "peer"))
            fixture.clock.millis += 300_000
            assertTrue(fixture.manager.validatePacket(packet(MessageType.MESSAGE, 1), "peer"))
        }
    }

    @Test
    fun announcementsHaveTheirOwnHardCapacityAndWindow() {
        manager().use { fixture ->
            repeat(MAX_RECENT_ANNOUNCEMENTS + 1) { assertTrue(fixture.manager.validatePacket(packet(MessageType.ANNOUNCE, it), "peer")) }
            assertEquals(MAX_RECENT_ANNOUNCEMENTS, fixture.manager.recentAnnouncementCount)
            assertTrue(fixture.manager.validatePacket(packet(MessageType.ANNOUNCE, 0), "peer"))
            assertFalse(fixture.manager.validatePacket(packet(MessageType.ANNOUNCE, MAX_RECENT_ANNOUNCEMENTS), "peer"))
            fixture.clock.millis += 60_000
            assertTrue(fixture.manager.validatePacket(packet(MessageType.ANNOUNCE, MAX_RECENT_ANNOUNCEMENTS), "peer"))
        }
    }

    private fun packet(type: MessageType, timestamp: Int) = BitchatPacket(
        type = type.value,
        senderID = byteArrayOf(1),
        timestamp = timestamp.toULong(),
        payload = byteArrayOf(),
        ttl = 1u
    )

    private fun manager(): Fixture {
        val crypto = CryptoSigningFacade("2".repeat(64))
        val clock = TestClock()
        return Fixture(SecurityManager(NoiseEncryptionFacade(crypto.getIdentityFingerprint()), crypto, crypto.getIdentityFingerprint(), clock), clock)
    }

    private class Fixture(val manager: SecurityManager, val clock: TestClock) : AutoCloseable {
        override fun close() = manager.shutdown()
    }

    private class TestClock(var millis: Long = 0) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }
}
