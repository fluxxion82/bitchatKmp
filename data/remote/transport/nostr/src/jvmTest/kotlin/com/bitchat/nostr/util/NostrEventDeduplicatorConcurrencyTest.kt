package com.bitchat.nostr.util

import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifying happens outside the deduplicator's lock, so several relays' copies of one event can all
 * pass the lookup before any of them is recorded. Recording is what decides: exactly one copy is
 * handed on.
 */
class NostrEventDeduplicatorConcurrencyTest {
    @Test
    fun `copies of one event that are all being verified at once are handed on once`() {
        val author = NostrIdentity.generate()
        val event = author.signEvent(
            NostrEvent(pubkey = author.publicKeyHex, createdAt = 1_780_000_000, kind = NostrKind.EPHEMERAL_EVENT, tags = emptyList(), content = "hello")
        )
        // No copy leaves the check until every copy is inside it: each of them found nothing
        // recorded, and none has recorded yet. A copy that never gets there fails the test here.
        val allInsideTheCheck = CyclicBarrier(COPIES)
        val deduplicator = NostrEventDeduplicator(maxCapacity = 10) { copy ->
            allInsideTheCheck.await(10, TimeUnit.SECONDS)
            copy.isValidSignature()
        }
        val delivered = AtomicInteger()
        val admissions = Array<EventAdmission?>(COPIES) { null }

        (0 until COPIES).map { index ->
            thread { admissions[index] = deduplicator.processEvent(event.copy()) { delivered.incrementAndGet() } }
        }.forEach { it.join() }

        assertEquals(1, delivered.get())
        assertEquals(1, admissions.count { it == EventAdmission.DELIVERED })
        assertEquals(COPIES - 1, admissions.count { it == EventAdmission.DUPLICATE })
    }

    private companion object {
        const val COPIES = 8
    }
}
