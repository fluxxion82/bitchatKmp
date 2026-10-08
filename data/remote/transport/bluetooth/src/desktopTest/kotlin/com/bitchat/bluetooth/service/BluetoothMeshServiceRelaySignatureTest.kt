package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.facade.CryptoSigningFacade
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Whose signature a packet carries when this device passes it on.
 *
 * A packet is signed by the device that made it, over its content with the hop count left out, so
 * that every device on the way can check it against the maker's key. The upstream clients do
 * exactly that: an ANNOUNCE whose signature does not verify under the signing key it announces is
 * ignored, and any other packet from a known peer is dropped. A relay that puts its own signature
 * on what it forwards therefore makes the packet worthless one hop on, and the device behind it is
 * never listed.
 */
class BluetoothMeshServiceRelaySignatureTest {

    @Test
    fun aRelayedPacketKeepsTheSignatureOfTheDeviceThatMadeIt() = runBlocking {
        val fixture = RelayFixture()
        try {
            val announce = fixture.announceFromMaker(ttl = 3u)

            fixture.receive(announce)

            val relayed = fixture.awaitRelayed(MessageType.ANNOUNCE)
            assertEquals(2u.toUByte(), relayed.ttl)
            assertContentEquals(announce.signature, relayed.signature)
            assertTrue(
                fixture.maker.verifySignature(
                    requireNotNull(relayed.toBinaryDataForSigning()),
                    requireNotNull(relayed.signature),
                    fixture.maker.getSigningPublicKey()
                ),
                "the relayed packet no longer verifies under its maker's key"
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aRelayedPacketThatCameUnsignedLeavesUnsigned() = runBlocking {
        val fixture = RelayFixture()
        try {
            val unsigned = fixture.announceFromMaker(ttl = 3u).copy(signature = null)

            fixture.receive(unsigned)

            val relayed = fixture.awaitRelayed(MessageType.ANNOUNCE)
            assertNull(relayed.signature, "this device signed a packet another device made")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun whatThisDeviceMakesIsStillSignedWithItsOwnKey() = runBlocking {
        val fixture = RelayFixture()
        try {
            fixture.service.sendBroadcastAnnounce()

            val own = fixture.awaitSent { it.senderID.contentEquals(fixture.service.myPeerID.hexToBytes()) }
            val signature = assertNotNull(own.signature, "this device's own packet left unsigned")
            assertTrue(
                fixture.local.verifySignature(
                    requireNotNull(own.toBinaryDataForSigning()), signature, fixture.local.getSigningPublicKey()
                )
            )
            assertFalse(
                fixture.maker.verifySignature(
                    requireNotNull(own.toBinaryDataForSigning()), signature, fixture.maker.getSigningPublicKey()
                )
            )
        } finally {
            fixture.close()
        }
    }
}

private class RelayFixture {
    val local = CryptoSigningFacade("1".repeat(64))
    val maker = CryptoSigningFacade("2".repeat(64))
    private val sent = CopyOnWriteArrayList<BitchatPacket>()

    val service = BluetoothMeshService(
        scanningService = object : CentralScanningService {
            override suspend fun startScan(lowLatency: Boolean) = Unit
            override suspend fun stopScan() = Unit
        },
        connectionService = object : BluetoothConnectionService {
            override suspend fun connectToDevice(deviceAddress: String) = Unit
            override suspend fun confirmDevice() = Unit
            override suspend fun isDeviceConnecting(deviceAddress: String) = false
            override suspend fun disconnectDeviceByAddress(deviceAddress: String) = Unit
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
        cryptoSigning = local
    )

    /** An ANNOUNCE as the device that made it sends it: signed by that device, hop count left out. */
    fun announceFromMaker(ttl: UByte): BitchatPacket {
        val payload = requireNotNull(
            IdentityAnnouncement("maker", maker.getNoisePublicKey(), maker.getSigningPublicKey()).encode()
        )
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            senderID = maker.getIdentityFingerprint().hexToBytes(),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = Clock.System.now().toEpochMilliseconds().toULong(),
            payload = payload,
            ttl = ttl
        )
        return packet.copy(signature = maker.signPacket(requireNotNull(packet.toBinaryDataForSigning())))
    }

    fun receive(packet: BitchatPacket) {
        service.onPacketReceived(requireNotNull(BinaryProtocol.encode(packet)), "address-of-the-neighbour")
    }

    suspend fun awaitRelayed(type: MessageType): BitchatPacket = awaitSent {
        it.type == type.value && it.senderID.contentEquals(maker.getIdentityFingerprint().hexToBytes())
    }

    suspend fun awaitSent(matching: (BitchatPacket) -> Boolean): BitchatPacket {
        val found = withContext(Dispatchers.Default) {
            withTimeoutOrNull(30.seconds) {
                var packet: BitchatPacket? = null
                while (packet == null) {
                    packet = sent.firstOrNull(matching)
                    if (packet == null) delay(10)
                }
                packet
            }
        }
        return assertNotNull(found, "timed out waiting for the packet to be handed to the links")
    }

    fun close() {
        service.stopServices()
    }
}

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
