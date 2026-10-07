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
import com.bitchat.lora.loRaHeartbeatNickname
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
import kotlinx.coroutines.test.TestScope
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

    // A bitchat heartbeat carries at most the first 24 bytes of a name. LONG_NAME and OTHER_LONG_NAME
    // are 25 bytes and differ in the last one, so both are heard as LONG_NAME_ON_AIR, which is 24
    // bytes and could itself be somebody's whole name.

    @Test fun aLongNameOnBothTransportsIsStillOneRow() = runTest {
        longNameCase(
            heard = mapOf(ALICE to LONG_NAME_ON_AIR),
            onMesh = arrayOf(ALICE to LONG_NAME),
            meshCopyFrom = ALICE to LONG_NAME,
            expectedRows = 1,
        )
    }

    @Test fun aNameJustShortEnoughToBeHeardWholeOnBothTransportsIsStillOneRow() = runTest {
        longNameCase(
            heard = mapOf(ALICE to LONG_NAME_ON_AIR),
            onMesh = arrayOf(ALICE to LONG_NAME_ON_AIR),
            meshCopyFrom = ALICE to LONG_NAME_ON_AIR,
            radioSender = LONG_NAME_ON_AIR,
            expectedRows = 1,
        )
    }

    @Test fun aDeviceHeardUnderTheStartOfALongNameAndUnknownToTheMeshMakesThatNameAmbiguous() = runTest {
        // It may be another device of this name: the radio copy must not be taken for the mesh peer's.
        longNameCase(
            heard = mapOf(OTHER_ALICE to LONG_NAME_ON_AIR),
            onMesh = arrayOf(ALICE to LONG_NAME),
            meshCopyFrom = ALICE to LONG_NAME,
            expectedRows = 2,
        )
    }

    @Test fun aDeviceThatOnlySharesTheStartOfALongNameIsNotTakenForItsSender() = runTest {
        // The mesh knows this device under its own name. Were it taken for the sender, the radio copy
        // (someone else's message) would be dropped as the twin of what this device said on the mesh.
        longNameCase(
            heard = mapOf(OTHER_ALICE to LONG_NAME_ON_AIR),
            onMesh = arrayOf(OTHER_ALICE to OTHER_LONG_NAME),
            meshCopyFrom = OTHER_ALICE to OTHER_LONG_NAME,
            expectedRows = 2,
        )
    }

    @Test fun aDeviceWithALongNameIsNotTakenForTheSenderWhoseWholeNameIsItsStart() = runTest {
        // What is heard of the long name is, letter for letter, another sender's whole name.
        longNameCase(
            heard = mapOf(ALICE to LONG_NAME_ON_AIR),
            onMesh = arrayOf(ALICE to LONG_NAME),
            meshCopyFrom = ALICE to LONG_NAME,
            radioSender = LONG_NAME_ON_AIR,
            expectedRows = 2,
        )
    }

    @Test fun aDeviceThatSharesTheStartOfALongNameMakesItAmbiguousWhateverTheMeshCallsThatDevice() = runTest {
        // What the mesh calls a device is only an announcement: it must not be able to take a
        // possible namesake out of the count.
        longNameCase(
            heard = mapOf(ALICE to LONG_NAME_ON_AIR, OTHER_ALICE to LONG_NAME_ON_AIR),
            onMesh = arrayOf(ALICE to LONG_NAME, OTHER_ALICE to OTHER_LONG_NAME),
            meshCopyFrom = ALICE to LONG_NAME,
            expectedRows = 2,
        )
    }

    @Test fun aNodeOfAnotherLoRaStackNamedLikeTheStartOfALongNameChangesNothing() = runTest {
        // Only bitchat heartbeats cut names: elsewhere those 24 bytes are simply another name.
        longNameCase(
            heard = mapOf("!a1b2c3d4" to LONG_NAME_ON_AIR),
            onMesh = arrayOf(ALICE to LONG_NAME),
            meshCopyFrom = ALICE to LONG_NAME,
            expectedRows = 1,
        )
    }

    /** A mesh message "hello", then the radio message `<radioSender>:hello`: how many rows are shown. */
    private suspend fun TestScope.longNameCase(
        heard: Map<String, String>,
        onMesh: Array<Pair<String, String>>,
        meshCopyFrom: Pair<String, String>,
        expectedRows: Int,
        radioSender: String = LONG_NAME,
    ) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            assertEquals(LONG_NAME_ON_AIR, loRaHeartbeatNickname(LONG_NAME))
            assertEquals(LONG_NAME_ON_AIR, loRaHeartbeatNickname(OTHER_LONG_NAME))
            val lora = FakeLoRaProtocol(heard)
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora, meshWith(*onMesh))
            chatRepo.didUpdatePeerList(onMesh.map { it.first })
            runCurrent()

            chatRepo.didReceiveMessage(meshMessage().copy(sender = meshCopyFrom.second, senderPeerID = meshCopyFrom.first))
            runCurrent()
            lora.receive("$radioSender:hello")
            runCurrent()

            assertEquals(expectedRows, chatRepo.getMeshMessages().size)
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

    private class FakeLoRaProtocol(heard: Map<String, String>) : LoRaProtocol {
        constructor(deviceIds: List<String> = listOf(ALICE), name: String = "alice") : this(deviceIds.associateWith { name })

        private val messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)

        override val peers: StateFlow<List<LoRaPeer>> = MutableStateFlow(
            heard.map { (deviceId, name) -> LoRaPeer(deviceId, name, Instant.fromEpochSeconds(0), -80, 6f) },
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
        val LONG_NAME_ON_AIR = "€".repeat(8)
        val LONG_NAME = LONG_NAME_ON_AIR + "a"
        val OTHER_LONG_NAME = LONG_NAME_ON_AIR + "b"
    }
}
