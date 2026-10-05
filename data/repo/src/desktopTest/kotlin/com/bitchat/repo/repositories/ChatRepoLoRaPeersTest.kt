package com.bitchat.repo.repositories

import com.bitchat.bluetooth.model.PeerInfo
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.chat.model.BitchatMessage
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepoLoRaPeersTest {
    @Test fun connectedMeshPeersAreNotReturnedAsLoRaPeers() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val mesh = mockk<BluetoothMeshService>(relaxed = true).also {
                every { it.myPeerID } returns "self"
                every { it.getPeerInfo("X") } returns peerInfo("X")
            }
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora = FakeLoRaProtocol(), mesh = mesh)
            runCurrent()

            chatRepo.didUpdatePeerList(listOf("X"))
            runCurrent()

            assertEquals(1, chatRepo.getLoRaPeers().size)
            assertEquals(listOf("lora-Y"), chatRepo.getLoRaPeers().map { it.id })
            assertEquals(listOf("lora-Y"), chatRepo.observeLoRaPeers().first().map { it.id })

            chatRepo.didUpdatePeerList(emptyList())
            runCurrent()

            assertEquals(listOf("lora-x", "lora-Y"), chatRepo.getLoRaPeers().map { it.id })
            assertEquals(listOf("lora-x", "lora-Y"), chatRepo.observeLoRaPeers().first().map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test fun aPrivateChatAloneNeverHidesALoRaPeer() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora = FakeLoRaProtocol())
            runCurrent()

            // Anyone in Bluetooth range can send a private message under any peer id; a peer that is
            // not connected over the mesh must stay listed as the LoRa peer it is.
            chatRepo.didReceiveMessage(
                BitchatMessage(
                    id = "dm-1",
                    sender = "X",
                    senderPeerID = "X",
                    content = "hello",
                    timestamp = Instant.fromEpochSeconds(0),
                    isPrivate = true,
                ),
            )
            runCurrent()

            assertEquals(listOf("lora-x", "lora-Y"), chatRepo.getLoRaPeers().map { it.id })
            assertEquals(listOf("lora-x", "lora-Y"), chatRepo.observeLoRaPeers().first().map { it.id })
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

    private class FakeLoRaProtocol : LoRaProtocol {
        override val peers: StateFlow<List<LoRaPeer>> = MutableStateFlow(
            listOf(
                LoRaPeer("x", "X", Instant.fromEpochSeconds(0), -80, 6f),
                LoRaPeer("Y", "Y", Instant.fromEpochSeconds(0), -80, 6f),
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
