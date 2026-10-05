package com.bitchat.nostr

import kotlin.test.Test
import kotlin.test.assertTrue

class NostrSubscriptionIdTest {
    @Test
    fun `every subscription id builder stays within the NIP-01 limit`() {
        val longValue = "a".repeat(256)
        val ids = listOf(
            NostrSubscriptionId.geohash(longValue),
            NostrSubscriptionId.sampling(longValue),
            NostrSubscriptionId.directMessages(longValue),
            NostrSubscriptionId.geohashDirectMessages(longValue),
            NostrSubscriptionId.notes(longValue, Int.MAX_VALUE),
            NostrSubscriptionId.channelMessages(longValue),
            NostrSubscriptionId.channelCreations(longValue),
            NostrSubscriptionId.allChannelCreations(),
        )

        ids.forEach { id ->
            assertTrue(id.length <= NostrSubscriptionId.MAX_LENGTH, "subscription id exceeds NIP-01 limit: $id")
        }
    }

}
