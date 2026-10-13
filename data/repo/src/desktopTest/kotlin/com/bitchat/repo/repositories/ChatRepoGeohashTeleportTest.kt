package com.bitchat.repo.repositories

import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrRelay
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.nostr.participant.NostrParticipantTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A geohash message says whether its sender is physically in the place, through the `teleport` tag.
 * Whether that may be claimed is decided in the location repository; this is what the chat sends,
 * including when that answer changes while the event is mined.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoGeohashTeleportTest {
    private val geohash = "9q8yy"
    private val identity = NostrIdentity.generate()

    private class Run(
        val client: NostrClient,
        val relay: NostrRelay,
        val tracker: NostrParticipantTracker,
        val location: LocationRepository,
        /** Every event the client was asked to create, in order. */
        val created: List<NostrEvent>,
    )

    @Test
    fun aMessageFromADeviceThatCannotBeThereIsSentAsTeleportedAndMarksItsOwnEntry() = send(true) { run ->
        coVerify(exactly = 1) { run.client.createEphemeralGeohashEvent("hello", geohash, identity, "me", true, any()) }
        coVerify(exactly = 1) { run.client.createEphemeralGeohashEvent(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { run.relay.sendEventToGeohash(run.created.single(), geohash, any(), any()) }
        coVerify { run.tracker.updateParticipant(eq(geohash), eq(identity.publicKeyHex), eq("me"), any(), eq(true)) }
        coVerify(exactly = 0) { run.tracker.updateParticipant(any(), any(), any(), any(), eq(false)) }
        coVerify(exactly = 1) { run.location.isTeleported() }
    }

    @Test
    fun aMessageFromADeviceThatMayBeThereIsSentAsBefore() = send(false, false) { run ->
        coVerify(exactly = 1) { run.client.createEphemeralGeohashEvent("hello", geohash, identity, "me", false, any()) }
        coVerify(exactly = 1) { run.client.createEphemeralGeohashEvent(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { run.relay.sendEventToGeohash(run.created.single(), geohash, any(), any()) }
        coVerify { run.tracker.updateParticipant(eq(geohash), eq(identity.publicKeyHex), eq("me"), any(), eq(false)) }
        coVerify(exactly = 0) { run.tracker.updateParticipant(any(), any(), any(), any(), eq(true)) }
    }

    @Test
    fun aDeviceThatStopsBeingThereWhileTheEventIsMinedIsSentAsTeleported() = send(false, true) { run ->
        val tempIds = mutableListOf<String>()
        coVerify(exactly = 2) { run.client.createEphemeralGeohashEvent(any(), any(), any(), any(), any(), capture(tempIds)) }
        coVerify(exactly = 1) { run.client.createEphemeralGeohashEvent("hello", geohash, identity, "me", false, any()) }
        coVerify(exactly = 1) { run.client.createEphemeralGeohashEvent("hello", geohash, identity, "me", true, any()) }
        assertTrue(tempIds.first().startsWith("mining-"), tempIds.toString())
        assertEquals(1, tempIds.toSet().size, "the second creation must follow the same local echo")
        // Only the tagged event is published, never the one made before the answer changed.
        val (untagged, tagged) = run.created
        verify(exactly = 1) { run.relay.sendEventToGeohash(any(), any(), any(), any()) }
        verify(exactly = 1) { run.relay.sendEventToGeohash(tagged, geohash, any(), any()) }
        verify(exactly = 0) { run.relay.sendEventToGeohash(untagged, any(), any(), any()) }
        coVerify { run.tracker.updateParticipant(eq(geohash), eq(identity.publicKeyHex), eq("me"), any(), eq(true)) }
        coVerify(exactly = 0) { run.tracker.updateParticipant(any(), any(), any(), any(), eq(false)) }
    }

    /** Sends one message while `isTeleported()` answers [answers] in turn, then checks what was done. */
    private fun send(vararg answers: Boolean, check: (Run) -> Unit) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val location = mockk<LocationRepository>(relaxed = true)
        coEvery { location.isTeleported() } returnsMany answers.toList()
        val tracker = mockk<NostrParticipantTracker>(relaxed = true)
        val relay = mockk<NostrRelay>(relaxed = true)
        every { relay.getRelaysForGeohash(any()) } returns listOf("wss://relay.example")
        val created = mutableListOf<NostrEvent>()
        var client: NostrClient? = null
        try {
            val repo = chatRepo(
                scope, dispatcher, mutableListOf(),
                geohashIdentity = identity,
                participantTracker = tracker,
                locationRepository = location,
                relay = relay,
                configureNostrClient = { boundary ->
                    client = boundary
                    coEvery { boundary.createEphemeralGeohashEvent(any(), any(), any(), any(), any(), any()) } coAnswers {
                        val teleported = arg<Boolean>(4)
                        val tags = listOf(listOf("g", geohash)) + if (teleported) listOf(listOf("t", "teleport")) else emptyList()
                        identity.signEvent(
                            NostrEvent(
                                pubkey = identity.publicKeyHex,
                                createdAt = 1_780_000_000,
                                kind = NostrKind.EPHEMERAL_EVENT,
                                tags = tags,
                                content = "hello",
                            )
                        ).also { created += it }
                    }
                },
            )

            repo.sendMessage("hello", Channel.Location(GeohashChannelLevel.CITY, geohash), "me", BitchatMessageType.Message)

            check(Run(client!!, relay, tracker, location, created))
        } finally {
            scope.cancel()
        }
    }
}
