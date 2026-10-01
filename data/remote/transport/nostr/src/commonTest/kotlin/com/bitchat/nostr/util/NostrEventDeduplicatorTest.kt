package com.bitchat.nostr.util

import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NostrRelay passes every incoming event through [NostrEventDeduplicator.processEvent] before any
 * subscription handler sees it, so a gift wrap dropped here never reaches ChatRepo at all.
 */
class NostrEventDeduplicatorTest {
    private val client = dmClient()
    private val sender = NostrIdentity.generate()
    private val recipient = NostrIdentity.generate()

    @Test
    fun `a gift wrap relabelled with a real wrap's id does not stop the real one`() {
        val deduplicator = NostrEventDeduplicator()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)
        val relabelled = client.giftWrap("bitchat1:decoy", NostrIdentity.generate(), recipient).copy(id = genuine.id)

        assertEquals(listOf(genuine), deduplicator.processAll(relabelled, genuine))
    }

    @Test
    fun `a real gift wrap under a forged signature does not stop the real one`() {
        val deduplicator = NostrEventDeduplicator()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)
        val forged = genuine.copy(sig = client.giftWrap("bitchat1:other", sender, recipient).sig)

        assertEquals(listOf(genuine), deduplicator.processAll(forged, genuine))
    }

    @Test
    fun `a gift wrap arriving from several relays is processed once`() {
        val deduplicator = NostrEventDeduplicator()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)

        assertEquals(listOf(genuine), deduplicator.processAll(genuine, genuine.copy(), genuine.copy()))
    }

    @Test
    fun `other kinds are still deduplicated by id alone`() {
        val deduplicator = NostrEventDeduplicator()
        val unsigned = NostrEvent(
            id = "00".repeat(32),
            pubkey = sender.publicKeyHex,
            createdAt = 1,
            kind = NostrKind.EPHEMERAL_EVENT,
            tags = listOf(listOf("g", "u4pruy")),
            content = "hello",
        )

        assertEquals(listOf(unsigned), deduplicator.processAll(unsigned, unsigned.copy()))
    }

    private fun NostrEventDeduplicator.processAll(vararg events: NostrEvent): List<NostrEvent> {
        val processed = mutableListOf<NostrEvent>()
        events.forEach { event -> processEvent(event) { processed += it } }
        return processed
    }
}
