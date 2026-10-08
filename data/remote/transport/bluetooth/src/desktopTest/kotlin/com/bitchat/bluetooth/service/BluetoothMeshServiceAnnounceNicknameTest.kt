package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.protocol.BinaryProtocol
import com.bitchat.bluetooth.protocol.IdentityAnnouncement
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.local.prefs.SecureStoreUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The nickname of an ANNOUNCE comes from the secure store, which does not answer on a locked
 * phone. The announce is then left out; nothing ends, and no other name goes out in its place.
 */
class BluetoothMeshServiceAnnounceNicknameTest {
    private val connection = AnnounceRecordingConnectionService()
    private val delegate = NicknameDelegate()
    private val service = BluetoothMeshService(
        scanningService = AnnounceNoOpScanningService,
        connectionService = connection,
        gattServerService = AnnounceNoOpGattServerService,
        advertisingService = AnnounceNoOpAdvertisingService,
        cryptoSigning = CryptoSigningFacade("1".repeat(64)),
    ).also { it.delegate = delegate }

    @Test
    fun `an announce whose nickname cannot be read ends quietly and sends nothing`() = runBlocking {
        delegate.answer = { throw SecureStoreUnavailableException("the Keychain is locked") }

        val announce = service.sendBroadcastAnnounce()
        announce.join()

        assertEquals(1, delegate.asked)
        // A coroutine that ended with an exception is a cancelled one: on Kotlin/Native that
        // exception, with nobody to take it, ends the process.
        assertFalse(announce.isCancelled, "the announce ended with an exception")
        assertNull(connection.nextAnnouncedNickname(within = 300.milliseconds), "an announce went out without the nickname")
    }

    @Test
    fun `the next announce goes out under the nickname once it can be read`() = runBlocking {
        delegate.answer = { throw SecureStoreUnavailableException("the Keychain is locked") }
        service.sendBroadcastAnnounce().join()
        delegate.answer = { "alice" }

        service.sendBroadcastAnnounce().join()

        assertEquals("alice", connection.nextAnnouncedNickname(within = 5.seconds))
    }

    @Test
    fun `the name to announce is the delegate's, and the given one while there is none`() {
        delegate.answer = { "alice" }
        assertEquals("alice", service.nicknameToAnnounce(otherwise = "Anonymous"))

        delegate.answer = { null }
        assertEquals("Anonymous", service.nicknameToAnnounce(otherwise = "Anonymous"))

        service.delegate = null
        assertEquals("Me", service.nicknameToAnnounce(otherwise = "Me"))
    }

    @Test
    fun `there is no name to announce while the store does not answer`() {
        delegate.answer = { throw SecureStoreUnavailableException("the Keychain is locked") }

        assertNull(service.nicknameToAnnounce(otherwise = "Anonymous"))
    }

    @Test
    fun `any other fault of the delegate is not taken for a locked store`() {
        delegate.answer = { throw IllegalArgumentException("a bug") }

        assertFailsWith<IllegalArgumentException> { service.nicknameToAnnounce(otherwise = "Anonymous") }
    }
}

private class NicknameDelegate : BluetoothMeshDelegate {
    @Volatile var answer: () -> String? = { null }
    @Volatile var asked = 0

    override fun getNickname(): String? {
        asked++
        return answer()
    }

    override fun didReceiveMessage(message: BitchatMessage) = Unit
    override fun didReceiveAuthenticatedPrivateMessage(message: BitchatMessage) = Unit
    override fun didUpdatePeerList(peers: List<String>) = Unit
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) = Unit
    override fun didReceiveAuthenticatedDeliveryAck(messageID: String, recipientPeerID: String) = Unit
    override fun didReceiveAuthenticatedReadReceipt(messageID: String, recipientPeerID: String) = Unit
    override suspend fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
    override fun isFavorite(peerID: String) = false
    override suspend fun onSessionEstablished(peerID: String) = Unit
    override fun didReceivePublicFile(peerID: String, filePacket: BitchatFilePacket) = Unit
    override fun didReceiveAuthenticatedPrivateFile(peerID: String, filePacket: BitchatFilePacket) = Unit
}

/** Keeps every packet handed to the links. */
private class AnnounceRecordingConnectionService : BluetoothConnectionService {
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun broadcastPacket(packetData: ByteArray): Boolean {
        outgoing.send(packetData)
        return true
    }

    /** The nickname of the next ANNOUNCE handed to the links, or null when none comes in time. */
    suspend fun nextAnnouncedNickname(within: Duration): String? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(within) {
            var nickname: String? = null
            while (nickname == null) {
                val packet = BinaryProtocol.decode(outgoing.receive()) ?: continue
                if (packet.type == MessageType.ANNOUNCE.value) nickname = IdentityAnnouncement.decode(packet.payload)?.nickname
            }
            nickname
        }
    }

    override suspend fun connectToDevice(deviceAddress: String) = Unit
    override suspend fun confirmDevice() = Unit
    override suspend fun isDeviceConnecting(deviceAddress: String) = false
    override suspend fun disconnectDeviceByAddress(deviceAddress: String) = Unit
    override suspend fun clearConnections() = Unit
    override fun hasRequiredPermissions() = true
    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) = Unit
    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) = Unit
    override fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback) = Unit
}

private object AnnounceNoOpScanningService : CentralScanningService {
    override suspend fun startScan(lowLatency: Boolean) = Unit
    override suspend fun stopScan() = Unit
}

private object AnnounceNoOpGattServerService : GattServerService {
    override suspend fun startAdvertising() = Unit
    override suspend fun stopAdvertising() = Unit
    override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) = Unit
    override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray) = true
    override fun setDelegate(delegate: GattServerDelegate) = Unit
}

private object AnnounceNoOpAdvertisingService : AdvertisingService {
    override suspend fun startAdvertising(serviceUuid: String, deviceName: String) = Unit
    override suspend fun stopAdvertising() = Unit
    override fun isAdvertising() = false
}
