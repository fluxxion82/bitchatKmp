package com.bitchat.nostr.util

import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * ChatRepo hands every incoming gift wrap to [HandledGiftWraps.acceptOnce] with
 * NostrClient.decryptPrivateMessage as the authenticate step, and processes the message only when
 * that returns a result. The first group of tests uses exactly that pairing on real envelopes.
 */
class HandledGiftWrapsTest {
    private val client = dmClient()
    private val sender = NostrIdentity.generate()
    private val recipient = NostrIdentity.generate()
    private val open = { wrap: NostrEvent -> client.decryptPrivateMessage(wrap, recipient) }
    private val accept = { wrap: NostrEvent -> wrap }

    @Test
    fun `an undecryptable envelope reusing a real message's id does not stop that message`() {
        val handled = HandledGiftWraps()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)
        val garbage = genuine.copy(content = "v2:" + "A".repeat(genuine.content.length - 3))

        assertNull(handled.acceptOnce(garbage, open))

        assertEquals("bitchat1:real", handled.acceptOnce(genuine, open)?.first)
    }

    @Test
    fun `a decryptable envelope relabelled with a real message's id does not stop that message`() {
        val handled = HandledGiftWraps()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)
        // The attacker's own, well-formed DM to us, carrying the id of the one it wants dropped.
        val relabelled = client.giftWrap("bitchat1:decoy", NostrIdentity.generate(), recipient).copy(id = genuine.id)

        assertNull(handled.acceptOnce(relabelled, open))

        assertEquals("bitchat1:real", handled.acceptOnce(genuine, open)?.first)
    }

    @Test
    fun `a real message's envelope under a forged signature does not stop that message`() {
        val handled = HandledGiftWraps()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)
        val otherSignature = client.giftWrap("bitchat1:other", sender, recipient).sig
        val forged = genuine.copy(sig = otherSignature)

        assertNull(handled.acceptOnce(forged, open))

        assertEquals("bitchat1:real", handled.acceptOnce(genuine, open)?.first)
    }

    @Test
    fun `the same authenticated message arriving from two relays is delivered once`() {
        val handled = HandledGiftWraps()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)

        assertNotNull(handled.acceptOnce(genuine, open))

        assertNull(handled.acceptOnce(genuine.copy(), open))
    }

    @Test
    fun `two copies racing through authentication are delivered once`() {
        val handled = HandledGiftWraps()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)
        var second: Triple<String, String, Int>? = null

        // The second relay's copy is authenticated and recorded while the first is still being opened.
        val first = handled.acceptOnce(genuine) { wrap ->
            second = handled.acceptOnce(genuine.copy(), open)
            open(wrap)
        }

        assertEquals(1, listOfNotNull(first, second).size)
    }

    @Test
    fun `a flood of authenticated envelopes cannot grow the set past its cap`() {
        val handled = HandledGiftWraps()
        val now = Clock.System.now().epochSeconds

        repeat(2 * HandledGiftWraps.DEFAULT_CAPACITY) { i ->
            handled.acceptOnce(syntheticGiftWrap("flood-$i", createdAt = now), accept)
        }

        assertEquals(HandledGiftWraps.DEFAULT_CAPACITY, handled.size())
    }

    @Test
    fun `a set full of fresh ids still delivers the next message by giving up the oldest id`() {
        val now = 1_000_000L
        val handled = HandledGiftWraps(capacity = 2, retentionSeconds = 100, nowEpochSeconds = { now })
        val first = syntheticGiftWrap("first", createdAt = now)
        val second = syntheticGiftWrap("second", createdAt = now)

        assertNotNull(handled.acceptOnce(first, accept))
        assertNotNull(handled.acceptOnce(second, accept))

        // Refusing here would let anyone black out DMs by sealing `capacity` wraps to this client.
        assertNotNull(handled.acceptOnce(syntheticGiftWrap("overflow", createdAt = now), accept))
        assertEquals(2, handled.size())
        // The ids still held keep suppressing their replays.
        assertNull(handled.acceptOnce(second.copy(), accept))
        // The price: the id that was given up to make room no longer suppresses its own replay.
        assertNotNull(handled.acceptOnce(first.copy(), accept))
    }

    @Test
    fun `a flood of wraps anyone can seal to us does not stop a real message`() {
        val handled = HandledGiftWraps(capacity = 4)
        // Each of these authenticates: the sender is a key the attacker made up, and the wrap is
        // sealed to us, so decryptPrivateMessage accepts it. Only ChatRepo's payload check drops it.
        repeat(2 * 4) { i ->
            val attacker = NostrIdentity.generate()
            assertNotNull(handled.acceptOnce(client.giftWrap("spam-$i", attacker, recipient), open))
        }

        assertEquals("bitchat1:real", handled.acceptOnce(client.giftWrap("bitchat1:real", sender, recipient), open)?.first)
    }

    @Test
    fun `making room never lets a still-fresh delivered message in again`() {
        var now = 1_000_000L
        val handled = HandledGiftWraps(capacity = 4, retentionSeconds = 100, nowEpochSeconds = { now })
        // Recorded first, so it is the least recently seen entry when room has to be made.
        val delivered = syntheticGiftWrap("delivered", createdAt = now)
        assertNotNull(handled.acceptOnce(delivered, accept))
        repeat(3) { i -> assertNotNull(handled.acceptOnce(syntheticGiftWrap("older-$i", createdAt = now - 90), accept)) }

        now += 11 // the three older wraps are now too old to accept; `delivered` has 89 s left
        assertNotNull(handled.acceptOnce(syntheticGiftWrap("new", createdAt = now), accept))

        assertNull(handled.acceptOnce(delivered.copy(), accept))
    }

    @Test
    fun `a delivered message whose id has been dropped is refused as too old`() {
        var now = 1_000_000L
        val handled = HandledGiftWraps(capacity = 1, retentionSeconds = 100, nowEpochSeconds = { now })
        val delivered = syntheticGiftWrap("delivered", createdAt = now)
        assertNotNull(handled.acceptOnce(delivered, accept))

        now += 101
        assertNotNull(handled.acceptOnce(syntheticGiftWrap("next", createdAt = now), accept))

        assertNull(handled.acceptOnce(delivered.copy(), accept))
    }

    @Test
    fun `a wrap backdated as far as NIP-59 allows is still accepted`() {
        val handled = HandledGiftWraps()
        val twoDaysAgo = Clock.System.now().epochSeconds - 2 * 24 * 3600

        assertNotNull(handled.acceptOnce(syntheticGiftWrap("backdated", createdAt = twoDaysAgo), accept))
    }
}
