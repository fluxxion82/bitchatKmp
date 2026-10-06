package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.manager.HandshakeSupervisor
import com.bitchat.bluetooth.protocol.BinaryProtocol
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.IdentityAnnouncement
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.SpecialRecipients
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Instant
import com.bitchat.bluetooth.manager.SessionFailureTracker
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BluetoothMeshServiceFallbackTest {

    @Test
    fun forgedPacketsDemoteAndBlockOutgoingTrafficWhileOldSessionTrafficStillDelivers() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()
        fixture.announce()

        repeat(3) { fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 7 }) }
        eventually("the failed session to be demoted") { !fixture.service.hasEstablishedSession(fixture.remoteID) }
        val firstOpener = fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        assertEquals(32, firstOpener.payload.size)
        assertTrue(fixture.service.isHandshakeInFlight(fixture.remoteID))

        fixture.service.sendPrivateMessage("must queue", fixture.remoteID, "remote", "outgoing")
        fixture.connection.assertNoEncryptedFrom(fixture.service.myPeerID)

        fixture.deliver(
            MessageType.NOISE_ENCRYPTED,
            assertNotNull(fixture.remoteNoise.encrypt(
                fixture.service.myPeerID,
                NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, PrivateMessagePacket("incoming", "still authenticates").encode()!!).encode()
            ))
        )
        eventually("the fallback ciphertext to reach the authenticated delegate") {
            fixture.delegate.messages == listOf("incoming" to "still authenticates")
        }

        // Message 1 was lost. This is the unchanged first-handshake sweeper, and it must not
        // destroy the fallback while replacing the candidate.
        val now = Clock.System.now().toEpochMilliseconds()
        fixture.service.sweepStalledHandshakes(now + HandshakeSupervisor.HANDSHAKE_TIMEOUT_MS + 1_000)
        val retriedOpener = fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        assertEquals(32, retriedOpener.payload.size)
        assertFalse(fixture.service.hasEstablishedSession(fixture.remoteID))

        val message2 = response(fixture.remoteNoise.processHandshake(
            fixture.service.myPeerID,
            retriedOpener.payload,
            fixture.remoteCrypto.getNoisePrivateKey(),
            fixture.remoteCrypto.getNoisePublicKey()
        ))
        fixture.deliver(MessageType.NOISE_HANDSHAKE, message2)
        val message3 = fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        fixture.remoteNoise.processHandshake(
            fixture.service.myPeerID,
            message3.payload,
            fixture.remoteCrypto.getNoisePrivateKey(),
            fixture.remoteCrypto.getNoisePublicKey()
        )
        eventually("the replacement session to establish") { fixture.service.hasEstablishedSession(fixture.remoteID) }
        fixture.service.sendPrivateMessage("works again", fixture.remoteID, "remote", "new")
        val encrypted = fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID)
        assertContentEquals(
            NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, PrivateMessagePacket("new", "works again").encode()!!).encode(),
            assertNotNull(fixture.remoteNoise.decrypt(fixture.service.myPeerID, encrypted.payload)).plaintext
        )
    }

    @Test
    fun exhaustedOrdinaryHandshakeBudgetAbandonsOnlyTheCandidateAndKeepsFallbackReadable() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()
        fixture.announce()
        repeat(3) { fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 9 }) }
        eventually("the fallback handshake to start") { fixture.service.isHandshakeInFlight(fixture.remoteID) }
        fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)

        val base = Clock.System.now().toEpochMilliseconds()
        listOf(10_100L, 30_100L, 70_100L, 150_100L).forEach { offset ->
            fixture.service.sweepStalledHandshakes(base + offset)
            assertEquals(32, fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID).payload.size)
        }
        fixture.service.sweepStalledHandshakes(base + 160_100L)
        fixture.connection.assertNoHandshakeQueued()
        assertFalse(fixture.service.hasEstablishedSession(fixture.remoteID))

        fixture.service.sendPrivateMessage("must queue", fixture.remoteID, "remote", "after-budget")
        fixture.connection.assertNoEncryptedFrom(fixture.service.myPeerID)
        fixture.deliver(
            MessageType.NOISE_ENCRYPTED,
            assertNotNull(fixture.remoteNoise.encrypt(
                fixture.service.myPeerID,
                NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, PrivateMessagePacket("incoming", "fallback remains readable").encode()!!).encode()
            ))
        )
        eventually("the fallback ciphertext to reach the authenticated delegate") {
            fixture.delegate.messages == listOf("incoming" to "fallback remains readable")
        }
    }

    @Test
    fun oldSessionMessagesTriggerNotSharedRecoveryAndNewSessionSendsNormally() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()
        fixture.announce()

        repeat(3) { fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 8 }) }
        val firstMessage1 = fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        val firstMessage2 = response(fixture.remoteNoise.processHandshake(
            fixture.service.myPeerID, firstMessage1.payload,
            fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
        ))
        fixture.deliver(MessageType.NOISE_HANDSHAKE, firstMessage2)
        fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID) // message 3 intentionally lost
        fixture.remoteNoise.processHandshake(
            fixture.service.myPeerID, byteArrayOf(1),
            fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
        )

        // The forged run above already used this peer's one recovery per cooldown: let it pass.
        fixture.failureClock.advanceBy(SessionFailureTracker.MIN_RECOVERY_INTERVAL_MS + 1)
        repeat(3) { index ->
            fixture.deliver(
                MessageType.NOISE_ENCRYPTED,
                assertNotNull(fixture.remoteNoise.encrypt(
                    fixture.service.myPeerID,
                    NoisePayload(
                        NoisePayloadType.PRIVATE_MESSAGE,
                        PrivateMessagePacket("old-$index", "delivered-$index").encode()!!
                    ).encode()
                ))
            )
        }
        // Each packet is handed to the mesh on its own coroutine, so the three can arrive in any order.
        eventually("all old-session messages to be delivered") {
            fixture.delegate.messages.size == 3 &&
                fixture.delegate.messages.toSet() == (0..2).map { "old-$it" to "delivered-$it" }.toSet()
        }
        eventually("the unshared established session to be discarded") {
            !fixture.service.hasEstablishedSession(fixture.remoteID)
        }
        val newMessage1 = fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        assertEquals(32, newMessage1.payload.size)

        // What was dropped is the session the peer never took up. The one it is still using must
        // keep reading until the new handshake completes.
        fixture.deliver(
            MessageType.NOISE_ENCRYPTED,
            assertNotNull(fixture.remoteNoise.encrypt(
                fixture.service.myPeerID,
                NoisePayload(
                    NoisePayloadType.PRIVATE_MESSAGE,
                    PrivateMessagePacket("old-3", "delivered-3").encode()!!
                ).encode()
            ))
        )
        eventually("an old-session message after the discard to be delivered") {
            fixture.delegate.messages.lastOrNull() == ("old-3" to "delivered-3")
        }

        val newMessage2 = response(fixture.remoteNoise.processHandshake(
            fixture.service.myPeerID, newMessage1.payload,
            fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
        ))
        fixture.deliver(MessageType.NOISE_HANDSHAKE, newMessage2)
        val newMessage3 = fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        fixture.remoteNoise.processHandshake(
            fixture.service.myPeerID, newMessage3.payload,
            fixture.remoteCrypto.getNoisePrivateKey(), fixture.remoteCrypto.getNoisePublicKey()
        )
        eventually("the replacement session to establish") { fixture.service.hasEstablishedSession(fixture.remoteID) }

        fixture.service.sendPrivateMessage("works again", fixture.remoteID, "remote", "new")
        val encrypted = fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID)
        assertContentEquals(
            NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, PrivateMessagePacket("new", "works again").encode()!!).encode(),
            assertNotNull(fixture.remoteNoise.decrypt(fixture.service.myPeerID, encrypted.payload)).plaintext
        )
    }
}

private class SettableClock : Clock {
    @Volatile private var millis = Clock.System.now().toEpochMilliseconds()
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    fun advanceBy(deltaMillis: Long) { millis += deltaMillis }
}

private class FallbackServiceFixture {
    val connection = FallbackRecordingConnectionService()
    private val localCrypto: CryptoSigningFacade
    val remoteCrypto: CryptoSigningFacade
    val service: BluetoothMeshService
    val remoteID: String
    val remoteNoise: NoiseEncryptionFacade
    val delegate = FallbackDelegate()
    /** The decrypt-failure cooldown's clock; a test moves it past the 30 s between two recoveries. */
    val failureClock = SettableClock()
    private var timestamp = 1uL

    init {
        val first = CryptoSigningFacade("1".repeat(64))
        val second = CryptoSigningFacade("2".repeat(64))
        if (first.getIdentityFingerprint() < second.getIdentityFingerprint()) {
            localCrypto = first
            remoteCrypto = second
        } else {
            localCrypto = second
            remoteCrypto = first
        }
        service = BluetoothMeshService(
            scanningService = FallbackNoOpScanningService,
            connectionService = connection,
            gattServerService = FallbackNoOpGattServerService,
            advertisingService = FallbackNoOpAdvertisingService,
            cryptoSigning = localCrypto,
            failureClock = failureClock
        )
        service.delegate = delegate
        remoteID = remoteCrypto.getIdentityFingerprint()
        remoteNoise = NoiseEncryptionFacade(remoteID)
    }

    suspend fun establish() {
        val message1 = remoteNoise.initiateHandshake(service.myPeerID, remoteCrypto.getNoisePrivateKey(), remoteCrypto.getNoisePublicKey())
        deliver(MessageType.NOISE_HANDSHAKE, message1)
        val message2 = connection.awaitHandshakeFrom(service.myPeerID)
        val message3 = response(remoteNoise.processHandshake(
            service.myPeerID, message2.payload, remoteCrypto.getNoisePrivateKey(), remoteCrypto.getNoisePublicKey()
        ))
        deliver(MessageType.NOISE_HANDSHAKE, message3)
        eventually("initial session") { service.hasEstablishedSession(remoteID) }
    }

    suspend fun announce() {
        val payload = requireNotNull(IdentityAnnouncement("remote", remoteCrypto.getNoisePublicKey(), remoteCrypto.getSigningPublicKey()).encode())
        deliver(MessageType.ANNOUNCE, payload, SpecialRecipients.BROADCAST)
        eventually("active remote peer") { service.getPeerInfo(remoteID) != null }
    }

    fun deliver(type: MessageType, payload: ByteArray, recipient: ByteArray = service.myPeerID.hexToBytes()) {
        val packet = BitchatPacket(type = type.value, senderID = remoteID.hexToBytes(), recipientID = recipient,
            timestamp = timestamp++, payload = payload, ttl = 1u)
        service.onPacketReceived(requireNotNull(BinaryProtocol.encode(packet)), "remote-address")
    }
}

private class FallbackRecordingConnectionService : BluetoothConnectionService {
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    override suspend fun connectToDevice(deviceAddress: String) = Unit
    override suspend fun confirmDevice() = Unit
    override suspend fun isDeviceConnecting(deviceAddress: String) = false
    override suspend fun disconnectDeviceByAddress(deviceAddress: String) = Unit
    override suspend fun clearConnections() = Unit
    override suspend fun broadcastPacket(packetData: ByteArray): Boolean { outgoing.send(packetData); return true }
    override fun hasRequiredPermissions() = true
    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) = Unit
    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) = Unit
    override fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback) = Unit

    suspend fun awaitHandshakeFrom(peerID: String): BitchatPacket = awaitPacket(peerID, MessageType.NOISE_HANDSHAKE)
    suspend fun awaitEncryptedFrom(peerID: String): BitchatPacket = awaitPacket(peerID, MessageType.NOISE_ENCRYPTED)
    suspend fun assertNoEncryptedFrom(peerID: String) {
        val packet = withContext(Dispatchers.Default) {
            withTimeoutOrNull(200.milliseconds) {
                while (true) {
                    val next = BinaryProtocol.decode(outgoing.receive()) ?: continue
                    if (next.type == MessageType.NOISE_ENCRYPTED.value && next.senderID.contentEquals(peerID.hexToBytes())) {
                        return@withTimeoutOrNull next
                    }
                }
                error("unreachable")
            }
        }
        assertNull(packet, "unexpected encrypted packet")
    }
    fun assertNoHandshakeQueued() {
        while (true) {
            val packet = BinaryProtocol.decode(outgoing.tryReceive().getOrNull() ?: return) ?: continue
            assertTrue(packet.type != MessageType.NOISE_HANDSHAKE.value, "unexpected handshake packet")
        }
    }
    private suspend fun awaitPacket(peerID: String, type: MessageType): BitchatPacket = withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            while (true) {
                val packet = BinaryProtocol.decode(outgoing.receive()) ?: continue
                if (packet.type == type.value && packet.senderID.contentEquals(peerID.hexToBytes())) return@withTimeout packet
            }
            error("unreachable")
        }
    }
}

private class FallbackDelegate : BluetoothMeshDelegate {
    val messages = mutableListOf<Pair<String, String>>()
    override fun didReceiveMessage(message: com.bitchat.domain.chat.model.BitchatMessage) = Unit
    override fun didReceiveAuthenticatedPrivateMessage(message: com.bitchat.domain.chat.model.BitchatMessage) {
        messages += message.id to message.content
    }
    override fun didUpdatePeerList(peers: List<String>) = Unit
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) = Unit
    override fun didReceiveAuthenticatedDeliveryAck(messageID: String, recipientPeerID: String) = Unit
    override fun didReceiveAuthenticatedReadReceipt(messageID: String, recipientPeerID: String) = Unit
    override suspend fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
    override fun getNickname(): String? = null
    override fun isFavorite(peerID: String) = false
    override suspend fun onSessionEstablished(peerID: String) = Unit
    override fun didReceivePublicFile(peerID: String, filePacket: BitchatFilePacket) = Unit
    override fun didReceiveAuthenticatedPrivateFile(peerID: String, filePacket: BitchatFilePacket) = Unit
}

private object FallbackNoOpScanningService : CentralScanningService {
    override suspend fun startScan(lowLatency: Boolean) = Unit
    override suspend fun stopScan() = Unit
}
private object FallbackNoOpGattServerService : GattServerService {
    override suspend fun startAdvertising() = Unit
    override suspend fun stopAdvertising() = Unit
    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) = Unit
    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray) = true
    override fun setDelegate(delegate: GattServerDelegate) = Unit
}
private object FallbackNoOpAdvertisingService : AdvertisingService {
    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) = Unit
    override suspend fun stopAdvertising() = Unit
    override fun isAdvertising() = false
}

private suspend fun eventually(description: String, condition: () -> Boolean) {
    // The service works on its own dispatcher in real time; runTest's clock is virtual.
    val reached = withContext(Dispatchers.Default) {
        withTimeoutOrNull(5.seconds) {
            while (!condition()) delay(10)
            true
        } ?: false
    }
    assertTrue(reached, "timed out waiting for $description")
}

private fun response(result: NoiseEncryptionFacade.HandshakeResult): ByteArray = when (result) {
    is NoiseEncryptionFacade.HandshakeResult.Response -> result.message
    is NoiseEncryptionFacade.HandshakeResult.Established -> result.response ?: error("expected response")
    NoiseEncryptionFacade.HandshakeResult.Ignored, NoiseEncryptionFacade.HandshakeResult.RejectedIdentity -> error("expected response, got $result")
}
private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
