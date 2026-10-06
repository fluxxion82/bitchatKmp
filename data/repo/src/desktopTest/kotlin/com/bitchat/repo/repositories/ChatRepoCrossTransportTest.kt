package com.bitchat.repo.repositories

import kotlinx.coroutines.CompletableDeferred
import io.mockk.coEvery
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.location.model.Channel
import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.LoRaProtocol
import com.bitchat.lora.radio.LoRaConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoCrossTransportTest {
    @Test fun bleThenLoRaShowsTheBleMessageOnce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol()
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora)
            runCurrent()

            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("alice:hello")
            runCurrent()

            val messages = chatRepo.getMeshMessages()
            assertEquals(1, messages.size)
            assertEquals("mesh-1", messages.single().id)
        } finally {
            scope.cancel()
        }
    }

    @Test fun loRaThenBleKeepsTheFirstCopyAndNeverReplacesIt() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol()
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora)
            runCurrent()

            lora.receive("alice:hello")
            runCurrent()
            val shownFirst = chatRepo.getMeshMessages().single()
            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()

            assertEquals(listOf(shownFirst), chatRepo.getMeshMessages())
        } finally {
            scope.cancel()
        }
    }

    @Test fun aNicknameSharedByTwoLoRaDevicesIsNeverPaired() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol(deviceIds = listOf(ALICE, OTHER_ALICE))
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora)
            runCurrent()

            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("alice:hello")
            runCurrent()

            assertEquals(2, chatRepo.getMeshMessages().size)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aRadioSendersNameIsCleanedBeforeItIsShown() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol(deviceIds = emptyList())
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora)
            runCurrent()

            lora.receive("alice#1a2b:hello")
            runCurrent()
            // Nothing is left of this one's name: its message is still shown, under the placeholder.
            lora.receive("#:hello again")
            runCurrent()

            assertEquals(listOf("alice1a2b", "Unknown"), chatRepo.getMeshMessages().map { it.sender })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aRadioSenderWhoseNameOnlyLooksLikeAMeshPeersOnceCleanedIsNotTakenForThatPeer() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            // "alice" is known on the mesh. Another device sends the same text as "ali#ce" over the radio
            // before any heartbeat of its own: shown as "alice" too, but never dropped as alice's copy.
            val lora = FakeLoRaProtocol(deviceIds = emptyList())
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora, mesh = meshWith(ALICE to "alice"))
            chatRepo.didUpdatePeerList(listOf(ALICE))
            runCurrent()

            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("ali#ce:hello")
            runCurrent()

            assertEquals(listOf("alice", "alice"), chatRepo.getMeshMessages().map { it.sender })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aRadioSenderNamedAfterTheStartOfAMeshPeersIdIsNotTakenForThatPeer() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            // The mesh peer announced no name. The start of its id is not what it is called: a radio sender
            // using that as a name is someone else.
            val lora = FakeLoRaProtocol(deviceIds = emptyList())
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora, mesh = meshWith(ALICE to "Unknown"))
            chatRepo.didUpdatePeerList(listOf(ALICE))
            runCurrent()
            assertEquals(listOf("Unknown"), chatRepo.getMeshPeers().map { it.displayName })

            chatRepo.didReceiveMessage(meshMessage().copy(sender = "Unknown"))
            runCurrent()
            lora.receive("${ALICE.take(12)}:hello")
            runCurrent()

            assertEquals(2, chatRepo.getMeshMessages().size)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aRadioSenderIsLookedUpByTheNamesOfThePeerListNotByWhatIsAnnouncedSince() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            // Two connected peers are both listed as "alice", so a radio "alice" is never paired. One of them
            // is then announced as "bob", and no peer-list update has run yet: the list still has two.
            val lora = FakeLoRaProtocol(deviceIds = emptyList())
            val mesh = meshWith(ALICE to "alice", OTHER_ALICE to "alice")
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora, mesh = mesh)
            chatRepo.didUpdatePeerList(listOf(ALICE, OTHER_ALICE))
            runCurrent()
            every { mesh.getPeerInfo(OTHER_ALICE) } returns PeerInfo(
                id = OTHER_ALICE,
                nickname = "bob",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = null,
                signingPublicKey = null,
                isVerifiedNickname = false,
                lastSeen = Instant.fromEpochSeconds(0),
            )

            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("alice:hello")
            runCurrent()

            assertEquals(2, chatRepo.getMeshMessages().size)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aPeerListUpdateThatWasHeldUpDoesNotPutOlderNamesOverANewerUpdates() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        // The first rename of an "Unknown" row, once armed, is held where it publishes its event.
        val release = CompletableDeferred<Unit>()
        var armed = false
        val bus = mockk<ChatEventBus>(relaxed = true)
        coEvery { bus.update(ChatEvent.MeshMessagesUpdated) } coAnswers {
            if (armed) {
                armed = false
                release.await()
            }
        }

        try {
            val lora = FakeLoRaProtocol(deviceIds = emptyList())
            val mesh = meshWith(ALICE to "alice", OTHER_ALICE to "bob")
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora, mesh = mesh, chatEventBus = bus)
            chatRepo.didReceiveMessage(meshMessage().copy(id = "old", sender = "Unknown", content = "earlier"))
            runCurrent()

            // The first update renames that row and is held; meanwhile the other peer is announced as
            // "alice" too and a second update runs to its end. The first one then finishes.
            armed = true
            chatRepo.didUpdatePeerList(listOf(ALICE, OTHER_ALICE))
            runCurrent()
            every { mesh.getPeerInfo(OTHER_ALICE) } returns PeerInfo(
                id = OTHER_ALICE,
                nickname = "alice",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = null,
                signingPublicKey = null,
                isVerifiedNickname = false,
                lastSeen = Instant.fromEpochSeconds(0),
            )
            chatRepo.didUpdatePeerList(listOf(ALICE, OTHER_ALICE))
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("alice", "alice"), chatRepo.getMeshPeers().map { it.displayName })

            // Two peers are "alice": a radio "alice" is never taken for the one that sent over the mesh.
            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("alice:hello")
            runCurrent()

            assertEquals(3, chatRepo.getMeshMessages().size)
        } finally {
            scope.cancel()
        }
    }

    @Test fun radioDevicesAreToldApartByTheNameAsItWasSent() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            // Two radio devices heartbeat as "ali#ce". A message sent as "alice" is neither of them, so the
            // name is not shared by two known devices and the copy pairs with the mesh message as before.
            val lora = FakeLoRaProtocol(deviceIds = listOf(ALICE, OTHER_ALICE), name = "ali#ce")
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora)
            runCurrent()

            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("alice:hello")
            runCurrent()

            assertEquals(listOf("mesh-1"), chatRepo.getMeshMessages().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aSenderKnownOnlyByNicknameIsStillPaired() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol(deviceIds = emptyList())
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora)
            runCurrent()

            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("alice:hello")
            runCurrent()

            assertEquals(listOf("mesh-1"), chatRepo.getMeshMessages().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aNicknameSharedByTwoMeshPeersIsNeverPaired() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol(deviceIds = listOf(ALICE))
            val mesh = meshWith(ALICE to "alice", OTHER_ALICE to "alice")
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora, mesh)
            runCurrent()
            chatRepo.didUpdatePeerList(listOf(ALICE, OTHER_ALICE))
            runCurrent()

            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("alice:hello")
            runCurrent()

            assertEquals(2, chatRepo.getMeshMessages().size)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aRenamedMeshPeerIsPairedByItsDeviceId() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol(deviceIds = emptyList())
            val mesh = meshWith(ALICE to "bob")
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora, mesh)
            runCurrent()
            chatRepo.didUpdatePeerList(listOf(ALICE))
            runCurrent()

            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()
            lora.receive("bob:hello")
            runCurrent()

            assertEquals(listOf("mesh-1"), chatRepo.getMeshMessages().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    private fun meshWith(vararg peers: Pair<String, String>) = mockk<BluetoothMeshService>(relaxed = true).also {
        every { it.myPeerID } returns "self"
        peers.forEach { (id, nickname) ->
            every { it.getPeerInfo(id) } returns PeerInfo(
                id = id,
                nickname = nickname,
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = null,
                signingPublicKey = null,
                isVerifiedNickname = false,
                lastSeen = Instant.fromEpochSeconds(0),
            )
        }
    }

    @Test fun clearingTheMeshChannelForgetsUnpairedLoRaCopies() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val clock = SteppedClock()

        try {
            val lora = FakeLoRaProtocol()
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora, clock = clock)
            runCurrent()

            lora.receive("alice:hello")
            runCurrent()
            chatRepo.clearMessages(Channel.Mesh)
            clock.advance()
            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()

            assertEquals(listOf("mesh-1"), chatRepo.getMeshMessages().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aBleCopyTheChannelAlreadyDroppedDoesNotHideItsLoRaTwin() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol()
            val chatRepo = chatRepo(
                scope, dispatcher, mutableListOf(), lora,
                messageLimits = com.bitchat.repo.utils.MessageLimits(maxMessagesPerChat = 2),
            )
            runCurrent()

            // The BLE copy arrives and is pushed out of the channel by two more messages, all inside
            // the pairing window.
            chatRepo.didReceiveMessage(meshMessage())
            chatRepo.didReceiveMessage(meshMessage().copy(id = "mesh-2", content = "second"))
            chatRepo.didReceiveMessage(meshMessage().copy(id = "mesh-3", content = "third"))
            runCurrent()
            assertEquals(listOf("mesh-2", "mesh-3"), chatRepo.getMeshMessages().map { it.id })

            lora.receive("alice:hello")
            runCurrent()

            // No copy of "hello" was on screen any more, so the LoRa one is shown.
            assertEquals(listOf("third", "hello"), chatRepo.getMeshMessages().map { it.content })
        } finally {
            scope.cancel()
        }
    }

    private class SteppedClock : Clock {
        private var current = Instant.fromEpochSeconds(1_000)

        override fun now(): Instant = current

        fun advance() {
            current += 1.seconds
        }
    }

    private fun meshMessage() = BitchatMessage(
        id = "mesh-1",
        sender = "alice",
        senderPeerID = ALICE,
        content = "hello",
        timestamp = Instant.fromEpochSeconds(0),
    )

    private class FakeLoRaProtocol(deviceIds: List<String> = listOf(ALICE), name: String = "alice") : LoRaProtocol {
        private val messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)

        override val peers: StateFlow<List<LoRaPeer>> = MutableStateFlow(
            deviceIds.map { LoRaPeer(it, name, Instant.fromEpochSeconds(0), -80, 6f) },
        )
        override val incomingMessages: Flow<ByteArray> = messages
        override val isReady = true
        override val protocolName = "test"
        override var deviceId = ""
        override var nickname = ""

        suspend fun receive(text: String) {
            messages.emit(text.encodeToByteArray())
        }

        override suspend fun start(config: LoRaConfig) = true
        override suspend fun stop() = Unit
        override suspend fun send(data: ByteArray) = true
    }

    private companion object {
        const val ALICE = "1111111111111111"
        const val OTHER_ALICE = "2222222222222222"
    }
}
