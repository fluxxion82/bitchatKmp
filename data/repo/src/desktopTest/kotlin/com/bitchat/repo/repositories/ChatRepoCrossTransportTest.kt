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

    @Test fun loRaThenBleShowsTheBleMessageOnce() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val lora = FakeLoRaProtocol()
            val chatRepo = chatRepo(scope, dispatcher, mutableListOf(), lora)
            runCurrent()

            lora.receive("alice:hello")
            runCurrent()
            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()

            val messages = chatRepo.getMeshMessages()
            assertEquals(1, messages.size)
            assertEquals("mesh-1", messages.single().id)
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
            lora.receive("alice:hello")
            runCurrent()
            clock.advance()
            chatRepo.didReceiveMessage(meshMessage())
            runCurrent()

            val messages = chatRepo.getMeshMessages()
            assertEquals(1, messages.size)
            assertEquals("mesh-1", messages.single().id)
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

    private class FakeLoRaProtocol(deviceIds: List<String> = listOf(ALICE)) : LoRaProtocol {
        private val messages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)

        override val peers: StateFlow<List<LoRaPeer>> = MutableStateFlow(
            deviceIds.map { LoRaPeer(it, "alice", Instant.fromEpochSeconds(0), -80, 6f) },
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
