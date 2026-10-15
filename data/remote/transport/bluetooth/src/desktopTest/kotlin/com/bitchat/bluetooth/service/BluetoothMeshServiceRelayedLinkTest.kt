package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.manager.HandshakeRefreshPolicy
import com.bitchat.bluetooth.manager.PeerLinkDirectory
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.bluetooth.protocol.BinaryProtocol
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.IdentityAnnouncement
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.SpecialRecipients
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Which link a packet tells this device about.
 *
 * A packet names the device that MADE it, and arrives on the link of the neighbour that handed it
 * over. A board with a direct link to a phone and a link to another board hears the phone's packets
 * on both; the copy relayed by the other board must not make the phone look as though it moved onto
 * that board's link, or the board drops its working link to the phone.
 */
class BluetoothMeshServiceRelayedLinkTest {

    @Test
    fun aPacketRelayedByANeighbourDoesNotEndTheLinkToItsMaker() = runBlocking {
        val fixture = RelayedLinkFixture()
        try {
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_LINK)
            fixture.receive(fixture.board.announce(ttl = 3u), on = BOARD_LINK)

            // The phone says nothing on its own link for longer than a link is taken to be live,
            // then one of its packets reaches this device through the other board.
            fixture.advance(PeerLinkDirectory.LIVE_LINK_WINDOW_MS + 1)
            fixture.receive(fixture.phone.announce(ttl = 2u), on = BOARD_LINK)

            assertEquals(emptyList(), fixture.disconnected)
            assertEquals(
                mapOf(PHONE_LINK to fixture.phone.id, BOARD_LINK to fixture.board.id),
                fixture.service.getDeviceAddressToPeerMapping()
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aPeerThatMovedToANewAddressStillHasTheOldLinkReleased() = runBlocking {
        for (made in listOf<UByte>(3u, 7u)) {
            val fixture = RelayedLinkFixture()
            try {
                fixture.receive(fixture.phone.announce(ttl = made), on = PHONE_LINK)

                fixture.advance(PeerLinkDirectory.LIVE_LINK_WINDOW_MS + 1)
                fixture.receive(fixture.phone.announce(ttl = made), on = PHONE_NEW_LINK)

                assertEquals(listOf(PHONE_LINK), fixture.disconnected, "made with $made")
                assertEquals(mapOf(PHONE_NEW_LINK to fixture.phone.id), fixture.service.getDeviceAddressToPeerMapping())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun aLinkThatCarriedARelayedPacketLatelyIsStillLive() = runBlocking {
        val fixture = RelayedLinkFixture()
        try {
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_LINK)

            // The phone hands over a packet the board made, 20 s later.
            fixture.advance(20_000)
            fixture.receive(fixture.board.announce(ttl = 2u), on = PHONE_LINK)

            // 40 s after its last packet of its own the phone turns up on a second link as well.
            // The first carried a packet 20 s ago, so it is live and stays.
            fixture.advance(20_000)
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_NEW_LINK)

            assertEquals(emptyList(), fixture.disconnected)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aPacketRelayedOnALinkNobodyIsBoundToBindsNothing() = runBlocking {
        for (arrived in listOf<UByte>(2u, 0u)) {
            val fixture = RelayedLinkFixture()
            try {
                fixture.receive(fixture.phone.message(ttl = arrived, text = "hello"), on = BOARD_LINK)

                assertEquals(emptyMap(), fixture.service.getDeviceAddressToPeerMapping(), "arrived with $arrived")
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun aPacketRelayedByANeighbourDoesNotRestartAHandshakeWithItsMaker() = runBlocking {
        val fixture = RelayedLinkFixture()
        try {
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_LINK)
            fixture.receive(fixture.board.announce(ttl = 3u), on = BOARD_LINK)
            fixture.service.initiateNoiseHandshake(fixture.phone.id)
            fixture.awaitOpenings(to = fixture.phone, count = 1)
            delay(HandshakeRefreshPolicy.HANDSHAKE_RESTART_GRACE_MS + 100)

            // Neither the phone again on the link it has, nor its packet passed on by the board.
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_LINK)
            fixture.receive(fixture.phone.announce(ttl = 2u), on = BOARD_LINK)
            // Long enough for a restart, had one been started, to have gone out, and for the next
            // link to be past the grace after it.
            delay(HandshakeRefreshPolicy.HANDSHAKE_RESTART_GRACE_MS + 100)
            assertEquals(1, fixture.openings(to = fixture.phone))

            // The phone itself on a new link is what restarts a handshake that has not been answered.
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_NEW_LINK)
            fixture.awaitOpenings(to = fixture.phone, count = 2)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aPeerOnTwoLiveLinksKeepsBothWhenOneCopyArrivesFirst() = runBlocking {
        val fixture = RelayedLinkFixture()
        try {
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_LINK)
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_NEW_LINK)

            // This app announces every 30 s, so a live link can be a moment past the window when the
            // copy on the other link is handled first.
            fixture.advance(PeerLinkDirectory.LIVE_LINK_WINDOW_MS + 1)
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_LINK)

            assertEquals(emptyList(), fixture.disconnected)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aPacketOfThisDevicesOwnHandedBackDoesNotKeepALinkLive() = runBlocking {
        val fixture = RelayedLinkFixture()
        try {
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_LINK)

            // The phone passes this device's own packet back to it, 20 s later.
            fixture.advance(20_000)
            fixture.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(fixture.self.announce(ttl = 2u))), PHONE_LINK)
            delay(200)

            // 40 s after the phone's last packet of its own it turns up on a new link.
            fixture.advance(20_000)
            fixture.receive(fixture.phone.announce(ttl = 3u), on = PHONE_NEW_LINK)

            assertEquals(listOf(PHONE_LINK), fixture.disconnected)
        } finally {
            fixture.close()
        }
    }

    private companion object {
        const val PHONE_LINK = "66:23:41:54:72:64"
        const val PHONE_NEW_LINK = "4C:8D:50:AF:FE:0E"
        const val BOARD_LINK = "90:82:8D:69:79:2D"
    }
}

private class Maker(seed: String) {
    val signing = CryptoSigningFacade(seed.repeat(64))
    val id: String = signing.getIdentityFingerprint()
    private var timestamp = Clock.System.now().toEpochMilliseconds().toULong()

    /** An ANNOUNCE as this device makes it, arriving with [ttl]; each one a new packet. */
    fun announce(ttl: UByte): BitchatPacket {
        val payload = requireNotNull(
            IdentityAnnouncement("maker", signing.getNoisePublicKey(), signing.getSigningPublicKey()).encode()
        )
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            senderID = id.hexToBytes(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = timestamp++,
            payload = payload,
            ttl = ttl
        )
        return packet.copy(signature = signing.signPacket(requireNotNull(packet.toBinaryDataForSigning())))
    }

    /** A public MESSAGE as this device makes it, arriving with [ttl]. */
    fun message(ttl: UByte, text: String) = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = id.hexToBytes(),
        recipientID = SpecialRecipients.BROADCAST,
        timestamp = timestamp++,
        payload = text.encodeToByteArray(),
        ttl = ttl
    )
}

private class RelayedLinkFixture {
    val phone = Maker("2")
    val board = Maker("3")
    val self = Maker("1")
    val disconnected = CopyOnWriteArrayList<String>()
    private val sent = CopyOnWriteArrayList<BitchatPacket>()
    private val delivered = CopyOnWriteArrayList<String>()

    @Volatile
    private var linkTime = Instant.fromEpochMilliseconds(1_000_000)

    val service = BluetoothMeshService(
        scanningService = object : CentralScanningService {
            override suspend fun startScan(lowLatency: Boolean) = Unit
            override suspend fun stopScan() = Unit
        },
        connectionService = object : BluetoothConnectionService {
            override suspend fun connectToDevice(deviceAddress: String) = Unit
            override suspend fun confirmDevice() = Unit
            override suspend fun isDeviceConnecting(deviceAddress: String) = false
            override suspend fun disconnectDeviceByAddress(deviceAddress: String) {
                disconnected += deviceAddress
            }
            override suspend fun clearConnections() = Unit
            override suspend fun broadcastPacket(packetData: ByteArray): Boolean {
                sent += requireNotNull(BinaryProtocol.decode(packetData))
                return true
            }
            override fun hasRequiredPermissions() = true
            override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) = Unit
            override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) = Unit
            override fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback) = Unit
        },
        gattServerService = object : GattServerService {
            override suspend fun startAdvertising() = Unit
            override suspend fun stopAdvertising() = Unit
            override suspend fun onCharacteristicWriteRequest(data: ByteArray, deviceAddress: String) = Unit
            override suspend fun notifyCharacteristic(deviceAddress: String, data: ByteArray) = true
            override fun setDelegate(delegate: GattServerDelegate) = Unit
        },
        advertisingService = object : AdvertisingService {
            override suspend fun startAdvertising(serviceUuid: String, deviceName: String) = Unit
            override suspend fun stopAdvertising() = Unit
            override fun isAdvertising() = false
        },
        cryptoSigning = self.signing,
        linkClock = object : Clock {
            override fun now() = linkTime
        }
    )

    init {
        service.delegate = object : BluetoothMeshDelegate {
            override fun didReceiveMessage(message: BitchatMessage) {
                delivered += message.content
            }
            override fun didReceiveAuthenticatedPrivateMessage(message: BitchatMessage) = Unit
            override fun didUpdatePeerList(peers: List<String>) = Unit
            override fun didReceiveChannelLeave(channel: String, fromPeer: String) = Unit
            override fun didReceiveAuthenticatedDeliveryAck(messageID: String, recipientPeerID: String) = Unit
            override fun didReceiveAuthenticatedReadReceipt(messageID: String, recipientPeerID: String) = Unit
            override fun getNickname(): String? = "local"
            override fun isFavorite(peerID: String) = false
            override suspend fun onSessionEstablished(peerID: String) = Unit
            override fun didReceivePublicFile(peerID: String, filePacket: BitchatFilePacket) = Unit
            override fun didReceiveAuthenticatedPrivateFile(peerID: String, filePacket: BitchatFilePacket) = Unit
        }
    }

    fun advance(ms: Long) {
        linkTime += ms.milliseconds
    }

    /**
     * Hand [packet] to the service as arriving on [on], and wait until the service has delivered a
     * MESSAGE or passed an ANNOUNCE on, which it does only after it has decided what the packet says
     * about the link. Packets are handled side by side, so the next one is handed over only then.
     */
    suspend fun receive(packet: BitchatPacket, on: String) {
        service.onPacketReceived(requireNotNull(BinaryProtocol.encode(packet)), on)
        if (packet.type == MessageType.MESSAGE.value) {
            val text = packet.payload.decodeToString()
            awaitThat("the message was not delivered") { text in delivered }
        } else {
            require(packet.ttl >= 2u) { "only a packet with hops left is passed on" }
            awaitThat("the packet was not passed on") {
                sent.any { it.senderID.contentEquals(packet.senderID) && it.timestamp == packet.timestamp }
            }
        }
    }

    /** The Noise handshake openings this device has sent to [to]. */
    fun openings(to: Maker): Int = sent.count {
        it.type == MessageType.NOISE_HANDSHAKE.value && it.recipientID?.contentEquals(to.id.hexToBytes()) == true
    }

    suspend fun awaitOpenings(to: Maker, count: Int) =
        awaitThat("$count handshake opening(s) were not sent") { openings(to) >= count }

    private suspend fun awaitThat(failure: String, condition: () -> Boolean) {
        val met = withContext(Dispatchers.Default) {
            withTimeoutOrNull(30.seconds) {
                while (!condition()) delay(10)
                true
            }
        }
        assertNotNull(met, failure)
        assertTrue(met)
    }

    fun close() {
        service.stopServices()
    }
}

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
