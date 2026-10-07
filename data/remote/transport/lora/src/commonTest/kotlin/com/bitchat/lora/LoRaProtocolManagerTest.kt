package com.bitchat.lora

import com.bitchat.lora.radio.LoRaConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoRaProtocolManagerTest {
    private class FakeProtocol(
        override val protocolName: String,
        override val peerIdsAreMeshIds: Boolean = false,
    ) : LoRaProtocol {
        override val peers = MutableStateFlow<List<LoRaPeer>>(emptyList())
        override val incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        override val incomingMeshPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        override var isReady = false
        override var deviceId = ""
        override var nickname = ""
        val configs = mutableListOf<LoRaConfig>()
        var stops = 0
        var failStop = false
        var startResult = true
        var readyOnStart = true
        override var supportsRadioConfiguration = true
        var onStart: suspend () -> Unit = {}
        override suspend fun start(config: LoRaConfig): Boolean {
            configs += config
            onStart()
            isReady = startResult && readyOnStart
            return startResult
        }
        override suspend fun stop() {
            stops++
            check(!failStop) { "owner did not stop" }
            isReady = false
        }
        override suspend fun send(data: ByteArray) = isReady
    }

    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        val bit = FakeProtocol("BitChat", peerIdsAreMeshIds = true)
        val mesh = FakeProtocol("MeshCore")
        val meshtastic = FakeProtocol("Meshtastic")
        val manager = LoRaProtocolManager(lazy { bit }, lazy { meshtastic }, lazy { mesh }, scope, readinessTimeoutMs = 500)
    }

    @Test fun peerIdentityCapabilityFollowsTheActiveProtocol() = runTest {
        val f = Fixture(backgroundScope)

        assertTrue(f.manager.peerIdsAreMeshIds)
        assertTrue(f.manager.switchProtocol(LoRaProtocolType.MESHCORE))
        assertFalse(f.manager.peerIdsAreMeshIds)
        assertTrue(f.manager.switchProtocol(LoRaProtocolType.MESHTASTIC))
        assertFalse(f.manager.peerIdsAreMeshIds)
    }

    @Test fun stopFailurePreventsNextProtocolFromStarting() = runTest {
        val f = Fixture(backgroundScope)
        f.manager.start()
        f.bit.failStop = true
        assertFalse(f.manager.switchProtocol(LoRaProtocolType.MESHCORE))
        assertTrue(f.mesh.configs.isEmpty())
    }

    @Test fun changedConfigurationRestartsTheSameProtocol() = runTest {
        val f = Fixture(backgroundScope)
        f.manager.start(LoRaConfig.US_915)
        val changed = LoRaConfig.US_915.copy(txPower = 10)
        assertTrue(f.manager.switchProtocol(LoRaProtocolType.BITCHAT, changed))
        assertEquals(listOf(LoRaConfig.US_915, changed), f.bit.configs)
        assertEquals(1, f.bit.stops)
    }

    @Test fun unchangedReadySelectionDoesNotRestart() = runTest {
        val f = Fixture(backgroundScope)
        f.manager.start(LoRaConfig.US_915)
        assertTrue(f.manager.switchProtocol(LoRaProtocolType.BITCHAT, LoRaConfig.US_915))
        assertEquals(1, f.bit.configs.size)
        assertEquals(0, f.bit.stops)
    }

    @Test fun existingMessageCollectorFollowsProtocolSwitches() = runTest {
        val f = Fixture(backgroundScope)
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.manager.incomingMessages.collect { seen += it[0].toInt() }
        }
        f.manager.start()
        f.bit.incomingMessages.emit(byteArrayOf(1))
        f.manager.switchProtocol(LoRaProtocolType.MESHCORE)
        f.mesh.incomingMessages.emit(byteArrayOf(2))
        f.bit.incomingMessages.emit(byteArrayOf(3))
        runCurrent()
        assertEquals(listOf(1, 2), seen)
    }

    @Test fun meshPacketCollectorFollowsTheActiveProtocol() = runTest {
        val f = Fixture(backgroundScope)
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.manager.incomingMeshPackets.collect { seen += it[0].toInt() }
        }
        f.manager.start()
        f.bit.incomingMeshPackets.emit(byteArrayOf(1))
        f.manager.switchProtocol(LoRaProtocolType.MESHCORE)
        f.mesh.incomingMeshPackets.emit(byteArrayOf(2))
        f.bit.incomingMeshPackets.emit(byteArrayOf(3))
        runCurrent()
        assertEquals(listOf(1, 2), seen)
    }

    @Test fun concurrentSwitchWaitsForStartingSession() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.bit.onStart = { entered.complete(Unit); release.await() }
        val first = async { f.manager.start() }
        entered.await()
        val second = async { f.manager.switchProtocol(LoRaProtocolType.MESHCORE) }
        runCurrent()
        try {
            assertTrue(f.mesh.configs.isEmpty(), "target started while previous start was in flight")
        } finally {
            release.complete(Unit)
        }
        first.await()
        second.await()
    }

    @Test fun failedStartCleansUpPartialTargetAndAllowsRetry() = runTest {
        val f = Fixture(backgroundScope)
        f.mesh.startResult = false
        assertFalse(f.manager.switchProtocol(LoRaProtocolType.MESHCORE))
        assertEquals(1, f.mesh.stops)
        f.mesh.startResult = true
        assertTrue(f.manager.switchProtocol(LoRaProtocolType.MESHCORE))
    }

    @Test fun newProtocolReceivesLocalIdentity() = runTest {
        val f = Fixture(backgroundScope)
        f.manager.deviceId = "abc123"
        f.manager.nickname = "Pi"
        f.manager.start()
        f.manager.switchProtocol(LoRaProtocolType.MESHCORE)
        assertEquals("abc123", f.mesh.deviceId)
        assertEquals("Pi", f.mesh.nickname)
    }

    @Test fun allSixSwitchDirectionsReleasePreviousOwner() = runTest {
        for (from in LoRaProtocolType.entries) for (to in LoRaProtocolType.entries) {
            if (from == to) continue
            val f = Fixture(backgroundScope)
            f.manager.setActiveType(from)
            assertTrue(f.manager.start())
            assertTrue(f.manager.switchProtocol(to))
            assertEquals(to, f.manager.activeType.value)
            assertEquals(1, listOf(f.bit, f.mesh, f.meshtastic).count { it.isReady })
            f.manager.stop()
        }
    }

    @Test fun readinessTimeoutCleansUpAndDoesNotAllowSending() = runTest {
        val f = Fixture(backgroundScope)
        f.mesh.readyOnStart = false
        assertFalse(f.manager.switchProtocol(LoRaProtocolType.MESHCORE))
        assertFalse(f.manager.isReady)
        assertFalse(f.manager.send(byteArrayOf(1)))
        assertEquals(1, f.mesh.stops)
    }

    @Test fun cancelledStartIsCleanedBeforeNextSelection() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        f.mesh.onStart = { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        val starting = launch { f.manager.switchProtocol(LoRaProtocolType.MESHCORE) }
        entered.await()
        starting.cancel()
        starting.join()
        assertEquals(1, f.mesh.stops)
        assertFalse(f.manager.isReady)
        assertTrue(f.manager.switchProtocol(LoRaProtocolType.MESHTASTIC))
    }

    @Test fun stopWaitsForInFlightStartAndLeavesNoOwner() = runTest {
        val f = Fixture(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.mesh.onStart = { entered.complete(Unit); release.await() }
        val starting = async { f.manager.switchProtocol(LoRaProtocolType.MESHCORE) }
        entered.await()
        val stopping = launch { f.manager.stop() }
        runCurrent()
        assertEquals(0, f.mesh.stops)
        release.complete(Unit)
        starting.await()
        stopping.join()
        assertFalse(f.manager.isReady)
        assertEquals(1, f.mesh.stops)
    }

    @Test fun unsupportedDaemonConfigurationReportsFailureWithoutRestart() = runTest {
        val f = Fixture(backgroundScope)
        f.mesh.supportsRadioConfiguration = false
        f.manager.switchProtocol(LoRaProtocolType.MESHCORE)
        assertFalse(f.manager.reconfigure(LoRaConfig.FAST))
        assertEquals(1, f.mesh.configs.size)
        assertEquals(0, f.mesh.stops)
        assertTrue(f.manager.switchProtocol(LoRaProtocolType.BITCHAT))
        assertEquals(LoRaConfig.FAST, f.bit.configs.last())
    }

    @Test fun failedSelectionClearsExistingPeerSubscription() = runTest {
        val f = Fixture(backgroundScope)
        val peers = f.manager.peers
        f.manager.start()
        f.bit.peers.value = listOf(LoRaPeer("one", "Old peer", kotlin.time.Instant.fromEpochSeconds(1), -80, 4f))
        runCurrent()
        assertEquals(1, peers.value.size)
        f.mesh.startResult = false
        assertFalse(f.manager.switchProtocol(LoRaProtocolType.MESHCORE))
        assertTrue(peers.value.isEmpty())
        f.bit.peers.value = emptyList()
        assertTrue(f.manager.peers === peers)
    }

    @Test fun initializationSelectorCannotBypassLifecycleAfterStop() = runTest {
        val f = Fixture(backgroundScope)
        f.manager.start()
        f.manager.stop()
        kotlin.test.assertFailsWith<IllegalStateException> {
            f.manager.setActiveType(LoRaProtocolType.MESHCORE)
        }
    }
}
