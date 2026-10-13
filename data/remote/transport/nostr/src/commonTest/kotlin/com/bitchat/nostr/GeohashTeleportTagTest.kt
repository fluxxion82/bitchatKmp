package com.bitchat.nostr

import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.util.geohashClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tag is what tells everyone else that the sender is not physically in the place, and the
 * receiving side reads exactly this tag. Nothing else on the wire says it.
 */
class GeohashTeleportTagTest {
    private val me = NostrIdentity.generate()
    private val teleportTag = listOf("t", "teleport")

    @Test
    fun `a teleported geohash message carries exactly one teleport tag`() = runTest {
        val event = geohashClient(powDifficulty = 0)
            .createEphemeralGeohashEvent("hello", "u4pruy", me, nickname = "me", teleported = true)

        assertEquals(1, event.tags.count { it == teleportTag }, event.tags.toString())
        assertEquals(listOf(listOf("g", "u4pruy")), event.tags.filter { it.firstOrNull() == "g" })
    }

    @Test
    fun `a geohash message that is not teleported carries no teleport tag`() = runTest {
        val event = geohashClient(powDifficulty = 0)
            .createEphemeralGeohashEvent("hello", "u4pruy", me, nickname = "me", teleported = false)

        assertEquals(0, event.tags.count { it.firstOrNull() == "t" }, event.tags.toString())
        assertEquals(listOf(listOf("g", "u4pruy")), event.tags.filter { it.firstOrNull() == "g" })
    }
}
