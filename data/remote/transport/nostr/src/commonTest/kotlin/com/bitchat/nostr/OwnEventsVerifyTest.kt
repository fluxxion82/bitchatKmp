package com.bitchat.nostr

import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.util.EventAdmission
import com.bitchat.nostr.util.NostrEventDeduplicator
import com.bitchat.nostr.util.geohashClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Relays echo what this client posts, and other copies of this app receive it, so everything it
 * creates has to pass the gate it puts incoming events through: [NostrEventDeduplicator.processEvent].
 */
class OwnEventsVerifyTest {
    private val me = NostrIdentity.generate()

    @Test
    fun `a geohash message this client creates verifies`() = runTest {
        val event = geohashClient(powDifficulty = 0)
            .createEphemeralGeohashEvent("hello", "u4pruy", me, nickname = "me", teleported = true)

        assertAdmitted(event)
    }

    @Test
    fun `a mined geohash message verifies and carries the work it claims`() = runTest {
        // Mining replaces the id and the timestamp after the event was first built; the signature
        // has to be over what is finally sent.
        val event = geohashClient(powDifficulty = MINED_BITS)
            .createEphemeralGeohashEvent("hello", "u4pruy", me, nickname = "me")

        assertAdmitted(event)
        assertTrue(NostrProofOfWork.validateDifficulty(event, MINED_BITS), "id ${event.id} does not carry $MINED_BITS bits")
    }

    @Test
    fun `a location note this client creates verifies`() = runTest {
        assertAdmitted(geohashClient(powDifficulty = 0).createGeohashTextNote("a note", "u4pruy", me, nickname = "me"))
    }

    @Test
    fun `a named channel and a message in it verify`() {
        val channel = NostrEvent.createChannelCreation(
            channelName = "#\"quoted\" name",
            about = "about",
            keyCommitment = "ab".repeat(32),
            publicKeyHex = me.publicKeyHex,
            privateKeyHex = me.privateKeyHex,
        )
        val message = NostrEvent.createChannelMessage(
            channelEventId = channel.id,
            relayUrl = "wss://relay.example",
            content = "hello",
            isEncrypted = true,
            nickname = "me",
            publicKeyHex = me.publicKeyHex,
            privateKeyHex = me.privateKeyHex,
        )

        assertAdmitted(channel)
        assertAdmitted(message)
    }

    private fun assertAdmitted(event: NostrEvent) {
        val handedOn = mutableListOf<NostrEvent>()

        val admission = NostrEventDeduplicator().processEvent(event) { handedOn += it }

        assertEquals(EventAdmission.DELIVERED to listOf(event), admission to handedOn, "kind ${event.kind}")
    }

    private companion object {
        /** Enough to show mining happened (one id in 256 has it by chance), few enough to mine in a test. */
        const val MINED_BITS = 8
    }
}
