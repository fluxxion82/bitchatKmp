package com.bitchat.repo.repositories

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoLoRaPeersTest {
    @Test fun bitchatPeersKeepRawIdsAndExposeMeshIdentity() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora = FakeLoRaProtocol(peerIdsAreMeshIds = true))

            val peers = chatRepo.getLoRaPeers()

            assertEquals(listOf("x", "Y"), peers.map { it.id })
            assertEquals(listOf("x", "Y"), peers.map { it.meshDeviceId })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aRadioPeersNameIsCleanedLikeAMeshNickname() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            // A number sign is the app's own in this list; a heartbeat with no usable name is listed under its id.
            val lora = FakeLoRaProtocol(peerIdsAreMeshIds = true, names = listOf("alice#1a2b\n", "#"))
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora = lora)

            assertEquals(listOf("alice1a2b", "Y"), chatRepo.getLoRaPeers().map { it.displayName })
        } finally {
            scope.cancel()
        }
    }

    @Test fun foreignLoRaPeersDoNotExposeMeshIdentity() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora = FakeLoRaProtocol(peerIdsAreMeshIds = false))

            assertEquals(listOf("x", "Y"), chatRepo.getLoRaPeers().map { it.id })
            chatRepo.getLoRaPeers().forEach { assertNull(it.meshDeviceId) }
        } finally {
            scope.cancel()
        }
    }

    @Test fun blockedBitchatIdentityIsNotObservedOverLoRa() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(
                scope,
                dispatcher,
                mutableListOf(),
                lora = FakeLoRaProtocol(peerIdsAreMeshIds = true),
                blockedMeshIds = setOf("x"),
            )

            assertEquals(listOf("Y"), chatRepo.getLoRaPeers().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aMeshPrivateChatKeepsTheNameItWasOpenedUnderWhateverLaterMessagesSay() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val mesh = mockk<BluetoothMeshService>(relaxed = true)
        fun announces(name: String) {
            every { mesh.getPeerInfo("X") } returns PeerInfo("X", name, true, true, null, null, false, Instant.fromEpochSeconds(0))
        }

        try {
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), mesh = mesh, lora = FakeLoRaProtocol(peerIdsAreMeshIds = true))
            runCurrent()

            announces("first name")
            chatRepo.didReceiveAuthenticatedPrivateMessage(privateMessage("dm-1", "first name", sentAt = 1))
            announces("third name")
            chatRepo.didReceiveAuthenticatedPrivateMessage(privateMessage("dm-3", "third name", sentAt = 3))
            chatRepo.didReceiveAuthenticatedPrivateMessage(privateMessage("dm-2", "second name", sentAt = 2))
            runCurrent()

            assertEquals(mapOf("X" to "first name#x"), chatRepo.getPrivateChatNames())
            assertEquals(Unit, chatRepo.observeLoRaPeerChanges().first())

            // Cleared, the conversation is still there and still called what it was; wiped, it is gone.
            chatRepo.clearMessages(Channel.MeshDM("X", "third name"))
            assertEquals(mapOf<String, String?>("X" to "first name#x"), chatRepo.getPrivateChatNames())
            chatRepo.clearData()
            assertEquals(emptyMap(), chatRepo.getPrivateChatNames())
        } finally {
            scope.cancel()
        }
    }

    private fun privateMessage(id: String, sender: String, sentAt: Long) = BitchatMessage(
        id = id,
        sender = sender,
        senderPeerID = "X",
        content = "hello",
        timestamp = Instant.fromEpochSeconds(sentAt),
        isPrivate = true,
    )

    @Test fun connectedMeshPeerIsStillObservedOverLoRa() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val mesh = mockk<BluetoothMeshService>(relaxed = true).also {
                every { it.myPeerID } returns "self"
                every { it.getPeerInfo("X") } returns peerInfo("X")
            }
            val chatRepo = chatRepo(
                scope,
                dispatcher,
                mutableListOf(),
                lora = FakeLoRaProtocol(peerIdsAreMeshIds = true),
                mesh = mesh,
            )
            chatRepo.didUpdatePeerList(listOf("X"))
            runCurrent()

            assertEquals(listOf("x", "Y"), chatRepo.getLoRaPeers().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    private fun peerInfo(id: String) = PeerInfo(
        id = id,
        nickname = id,
        isConnected = true,
        isDirectConnection = true,
        noisePublicKey = null,
        signingPublicKey = null,
        isVerifiedNickname = false,
        lastSeen = Instant.fromEpochSeconds(0),
    )

    private class FakeLoRaProtocol(
        override val peerIdsAreMeshIds: Boolean,
        names: List<String> = listOf("X", "Y"),
    ) : LoRaProtocol {
        override val peers: StateFlow<List<LoRaPeer>> = MutableStateFlow(
            listOf(
                LoRaPeer("x", names[0], Instant.fromEpochSeconds(0), -80, 6f),
                LoRaPeer("Y", names[1], Instant.fromEpochSeconds(0), -80, 6f),
            ),
        )
        override val incomingMessages: Flow<ByteArray> = emptyFlow()
        override val isReady = true
        override val protocolName = "test"
        override var deviceId = ""
        override var nickname = ""

        override suspend fun start(config: LoRaConfig) = true
        override suspend fun stop() = Unit
        override suspend fun send(data: ByteArray) = true
    }
}
