package com.bitchat.bluetooth.service

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.manager.HandshakeSupervisor
import com.bitchat.bluetooth.manager.HandshakeRefreshPolicy
import com.bitchat.bluetooth.protocol.BinaryProtocol
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.IdentityAnnouncement
import com.bitchat.bluetooth.protocol.LoRaRejection
import com.bitchat.bluetooth.protocol.MAX_LORA_PACKET_BYTES
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.MessagePadding
import com.bitchat.bluetooth.protocol.SpecialRecipients
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.domain.chat.model.PrivateMessageText
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import com.bitchat.transport.MeshRadioLink
import com.bitchat.transport.RadioPurpose
import com.bitchat.transport.RadioSendResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class BluetoothMeshServiceLoRaIngressTest {
    @Test
    fun everyRejectedRadioPacketLeavesNoStateForItsClaimedSender() = runTest {
        for (reason in LoRaRejection.entries) {
            val fixture = FallbackServiceFixture()
            val sender = if (reason == LoRaRejection.FROM_THIS_DEVICE) fixture.service.myPeerID else fixture.remoteID
            val data = when (reason) {
                LoRaRejection.TOO_LARGE -> exactPacket(sender, fixture.service.myPeerID, payload = ByteArray(203) { it.toByte() })
                LoRaRejection.NOT_A_PACKET -> byteArrayOf(1)
                LoRaRejection.NOT_FOR_THIS_DEVICE -> exactPacket(sender, "2122232425262728")
                LoRaRejection.FROM_THIS_DEVICE -> exactPacket(sender, fixture.service.myPeerID)
                LoRaRejection.WRONG_TYPE -> exactPacket(sender, fixture.service.myPeerID, type = MessageType.ANNOUNCE)
                LoRaRejection.WRONG_TTL -> exactPacket(sender, fixture.service.myPeerID, ttl = 1u)
            }
            fixture.service.onLoRaPacketReceived(data)
            fixture.service.assertNoLoRaIngressState(sender)
        }
    }

    @Test
    fun radioHandshakeAdmissionsShareTheRadioLinkWithoutBindingPeerAddresses() = runTest {
        val fixture = FallbackServiceFixture()
        val remotes = (3..11).map { FallbackRemote(it.toString(16)) }

        remotes.take(8).forEach { remote ->
            val opening = remote.noise.initiateHandshake(
                fixture.service.myPeerID, remote.crypto.getNoisePrivateKey(), remote.crypto.getNoisePublicKey()
            )
            fixture.service.onLoRaPacketReceived(exactPacket(remote.id, fixture.service.myPeerID, payload = opening))
            eventually("radio opening from ${remote.id.take(8)} to be admitted") {
                fixture.service.hasNoiseCandidate(remote.id)
            }
        }
        val ninth = remotes.last()
        val ninthOpening = ninth.noise.initiateHandshake(
            fixture.service.myPeerID, ninth.crypto.getNoisePrivateKey(), ninth.crypto.getNoisePublicKey()
        )
        fixture.service.onLoRaPacketReceived(exactPacket(ninth.id, fixture.service.myPeerID, payload = ninthOpening))
        assertTrue(fixture.service.getDeviceAddressToPeerMapping().isEmpty())

        val bluetoothRemote = FallbackRemote("c")
        val bluetoothOpening = bluetoothRemote.noise.initiateHandshake(
            fixture.service.myPeerID, bluetoothRemote.crypto.getNoisePrivateKey(), bluetoothRemote.crypto.getNoisePublicKey()
        )
        fixture.service.onPacketReceived(
            exactPacket(bluetoothRemote.id, fixture.service.myPeerID, payload = bluetoothOpening), "bluetooth-address"
        )
        eventually("a Bluetooth opening to use its own admission link") {
            fixture.service.hasNoiseCandidate(bluetoothRemote.id)
        }
        // A connection the platform gives no address for is yet another link than the radio: the
        // radio's name is its own, not the absence of one.
        val unaddressedRemote = FallbackRemote("d")
        val unaddressedOpening = unaddressedRemote.noise.initiateHandshake(
            fixture.service.myPeerID, unaddressedRemote.crypto.getNoisePrivateKey(), unaddressedRemote.crypto.getNoisePublicKey()
        )
        fixture.service.onPacketReceived(
            exactPacket(unaddressedRemote.id, fixture.service.myPeerID, payload = unaddressedOpening), ""
        )
        eventually("an opening from a connection without an address not to count against the radio") {
            fixture.service.hasNoiseCandidate(unaddressedRemote.id)
        }
        // The ninth radio opening was handed over before the Bluetooth one, which has been processed by
        // now; it is watched a little longer all the same, inside the three seconds its link stays full.
        never("the ninth radio opening to be admitted", forMillis = 500) {
            fixture.service.hasNoiseCandidate(ninth.id)
        }
        assertEquals("bluetooth-address", fixture.service.getDeviceAddressForPeer(bluetoothRemote.id))
    }

    @Test
    fun radioPacketClaimingThisDeviceAsSenderIsDroppedBeforeNoiseStateExists() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.service.onLoRaPacketReceived(exactPacket(fixture.service.myPeerID, fixture.service.myPeerID))

        fixture.service.assertNoLoRaIngressState(fixture.service.myPeerID)
    }
}

/**
 * Two devices that hear each other on the radio and have no Bluetooth link, unless a test gives them
 * one: whatever one hands to its radio link arrives at the other's radio ingress.
 */
class BluetoothMeshServiceRadioLinkTest {
    private val messageId = "0".repeat(36)

    @Test
    fun aPeerHeardOnlyOnTheRadioGetsItsHandshakeThereInThreeExactPackets() = runTest {
        val pair = RadioPair()
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        pair.awaitSessions()

        assertEquals(
            listOf(
                pair.a.id to RadioPurpose.HandshakeOpening(byUser = true),
                pair.b.id to RadioPurpose.HandshakeAnswer,
                pair.a.id to RadioPurpose.HandshakeFinal,
            ),
            pair.radio.sent.map { it.from to it.purpose },
        )
        pair.radio.sent.forEach { assertRadioForm(it, MessageType.NOISE_HANDSHAKE) }
        assertEquals(0, pair.a.connection.broadcastAttempts)
        assertEquals(0, pair.b.connection.broadcastAttempts)
    }

    @Test
    fun aPrivateMessageToSuchAPeerGoesOverTheRadioAsOneExactPacket() = runTest {
        val pair = RadioPair().established()
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("the message to arrive") { pair.b.delegate.messages.toList() == listOf(messageId to "hello") }

        val sent = pair.radio.sent.single { it.purpose == RadioPurpose.PrivateMessage }
        assertEquals(pair.a.id, sent.from)
        assertRadioForm(sent, MessageType.NOISE_ENCRYPTED)
        assertEquals(0, pair.a.connection.broadcastAttempts)
    }

    @Test
    fun anAnswerGoesBackOverTheRadioEvenToAPeerThatIsAlsoOnTheMesh() = runTest {
        val pair = RadioPair()
        pair.b.announceTo(pair.a)
        // b does not see a on the mesh, so its opening comes over the radio.
        pair.b.service.initiateNoiseHandshake(pair.a.id)
        pair.awaitSessions()

        assertEquals(RadioPurpose.HandshakeAnswer, pair.radio.sent.single { it.from == pair.a.id }.purpose)
        pair.a.connection.assertNoHandshakeFrom(pair.a.id)
    }

    @Test
    fun anAnswerToAnOpeningThatCameOverBluetoothGoesOverBluetoothEvenToAPeerHeardOnTheRadio() = runTest {
        val pair = RadioPair()
        pair.a.announceTo(pair.b)
        // b sees a on the mesh, so its opening goes out over Bluetooth; handed to a as if a link carried it.
        pair.b.service.initiateNoiseHandshake(pair.a.id)
        val opening = pair.b.connection.awaitHandshakeFrom(pair.b.id)
        pair.a.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(opening)), "address-of-b")

        assertNotNull(pair.a.connection.awaitHandshakeFrom(pair.a.id))
        assertTrue(pair.radio.sent.isEmpty())
    }

    @Test
    fun whatThisDeviceStartsGoesOverBluetoothWheneverTheMeshHasThePeer() = runTest {
        val pair = RadioPair()
        pair.b.announceTo(pair.a)
        assertFalse(pair.a.service.reachesByRadio(pair.b.id))
        pair.a.service.initiateNoiseHandshake(pair.b.id)

        assertNotNull(pair.a.connection.awaitHandshakeFrom(pair.a.id))
        assertTrue(pair.radio.sent.isEmpty())
        assertEquals(PrivateMessageText.MAX_BYTES, pair.a.service.privateTextLimitFor(pair.b.id))
    }

    @Test
    fun aSessionMadeOverBluetoothIsNotUsedOverTheRadioOnAHeartbeatsWord() = runTest {
        // The session comes about over Bluetooth, before either device has a radio.
        val pair = RadioPair(joined = false)
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        pair.carryHandshakeOverBluetooth(from = pair.a, to = pair.b)
        pair.carryHandshakeOverBluetooth(from = pair.b, to = pair.a)
        pair.carryHandshakeOverBluetooth(from = pair.a, to = pair.b)
        pair.awaitSessions()

        // Now each hears the other on the radio (or someone who says so), and neither is on the other's mesh.
        pair.join()
        assertFalse(pair.a.service.reachesByRadio(pair.b.id))
        assertEquals(PrivateMessageText.MAX_BYTES, pair.a.service.privateTextLimitFor(pair.b.id))
        // The repository hands such a peer nothing (it is not reachable). Should a message for it
        // arrive here all the same, it goes nowhere and says so: not over the radio in that session,
        // and not to Bluetooth links that are not this peer's.
        val writesBefore = pair.a.connection.broadcastAttempts
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("the message to be reported as not sent") {
            pair.a.delegate.failures.toList() == listOf(messageId to BluetoothMeshService.OUT_OF_REACH)
        }
        assertTrue(pair.radio.sent.isEmpty())
        assertEquals(writesBefore, pair.a.connection.broadcastAttempts)

        // Once the mesh has the peer, the session made over Bluetooth is used over Bluetooth.
        pair.b.announceTo(pair.a)
        assertTrue(pair.a.service.sendPrivateMessage("hello again", pair.b.id, "b", "2".repeat(36)))
        assertNotNull(pair.a.connection.awaitEncryptedFrom(pair.a.id))
        assertTrue(pair.radio.sent.isEmpty())
    }

    @Test
    fun aSessionMadeAgainOverBluetoothIsNoLongerARadioSession() = runTest {
        val pair = RadioPair().established()
        assertTrue(pair.a.service.reachesByRadio(pair.b.id))
        // b comes to see a on the mesh, loses trust in the session and makes a new one, over Bluetooth.
        pair.a.announceTo(pair.b)
        repeat(3) { round ->
            pair.b.service.onLoRaPacketReceived(
                exactPacket(pair.a.id, pair.b.id, MessageType.NOISE_ENCRYPTED, ByteArray(40) { (it + round).toByte() })
            )
        }
        val onTheRadioBefore = pair.radio.sent.size
        pair.carryHandshakeOverBluetooth(from = pair.b, to = pair.a)
        pair.carryHandshakeOverBluetooth(from = pair.a, to = pair.b)
        pair.carryHandshakeOverBluetooth(from = pair.b, to = pair.a)

        eventually("the session made over Bluetooth to replace the radio one") { !pair.a.service.reachesByRadio(pair.b.id) }
        assertEquals(onTheRadioBefore, pair.radio.sent.size)
        assertEquals(PrivateMessageText.MAX_BYTES, pair.a.service.privateTextLimitFor(pair.b.id))
    }

    @Test
    fun whatWasEncryptedInASessionMadeOverBluetoothIsNeverOfferedToTheRadio() = runTest {
        val pair = RadioPair().established()
        // b has a on its mesh and loses trust in the session: it opens a new handshake over
        // Bluetooth, which waits on b's links until it is carried over.
        pair.a.announceTo(pair.b)
        repeat(3) { round ->
            pair.b.service.onLoRaPacketReceived(
                exactPacket(pair.a.id, pair.b.id, MessageType.NOISE_ENCRYPTED, ByteArray(40) { (it + round).toByte() })
            )
        }
        // a's mesh never has b. When a's message is about to be encrypted, a looks at its way out
        // to b: the session is the one made over the radio. Before that look is over, and so before
        // the message is encrypted, the handshake over Bluetooth completes and its session stands.
        val looks = AtomicInteger()
        pair.radio.stillHeard = { owner, _ ->
            if (owner == pair.a.id && looks.incrementAndGet() == 2) {
                runBlocking {
                    pair.carryHandshakeOverBluetooth(from = pair.b, to = pair.a)
                    pair.carryHandshakeOverBluetooth(from = pair.a, to = pair.b)
                    pair.carryHandshakeOverBluetooth(from = pair.b, to = pair.a)
                    eventually("the session made over Bluetooth to stand") { !pair.a.service.reachesByRadio(pair.b.id) }
                }
            }
            true
        }
        val offeredBefore = pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage }
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))

        // The look said radio, the radio still hears b, and yet nothing of it goes there.
        eventually("the message to be reported as not sent") {
            pair.a.delegate.failures.toList() == listOf(messageId to BluetoothMeshService.OUT_OF_REACH)
        }
        assertEquals(offeredBefore, pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage })
    }

    @Test
    fun aHandshakeStartedAgainBecauseOfAForgedPacketIsNotPaidByTheUserAgain() = runTest {
        val pair = RadioPair()
        // b never hears a's opening, so the user's handshake stays in flight.
        pair.radio.passOn = { false }
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        eventually("the user's opening to be recorded") { pair.a.service.handshakeSupervisorSize() == 1 }
        withContext(Dispatchers.Default) { delay(HandshakeRefreshPolicy.HANDSHAKE_RESTART_GRACE_MS + 300) }

        // Any packet under b's id from an address a has not seen b on, readable or not: no key is
        // needed for it, and it makes a start the handshake again.
        val forged = BitchatPacket(
            type = MessageType.NOISE_ENCRYPTED.value, senderID = pair.b.id.hexToBytes(),
            recipientID = "0f0e0d0c0b0a0908".hexToBytes(), timestamp = 7u, payload = ByteArray(40) { it.toByte() }, ttl = 1u,
        )
        pair.a.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(forged)), "an-address-never-seen")

        eventually("the handshake to be started again") { pair.radio.sent.size == 2 }
        assertEquals(
            listOf(RadioPurpose.HandshakeOpening(byUser = true), RadioPurpose.HandshakeOpening(byUser = false)),
            pair.radio.sent.map { it.purpose },
        )
    }

    @Test
    fun aRadioMessageWaitingForTheAirGoesOverBluetoothOnceTheMeshHasThePeer() = runTest {
        val pair = RadioPair(radioRetryMs = 20).established()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage) RadioSendResult.NO_TIME_ON_AIR else RadioSendResult.SENT
        }
        fun offers() = pair.radio.sent.filter { it.purpose == RadioPurpose.PrivateMessage }
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("it to be offered to the radio") { offers().isNotEmpty() }

        pair.b.announceTo(pair.a)
        val overBluetooth = pair.a.connection.awaitEncryptedFrom(pair.a.id)
        // The very message that was waiting, not another encryption of it.
        assertContentEquals(assertNotNull(BinaryProtocol.decodeExact(offers().last().bytes)).payload, overBluetooth.payload)
        val offered = offers().size
        never("another offer to the radio", forMillis = 200) { offers().size != offered }
        assertTrue(pair.a.delegate.failures.isEmpty())
    }

    @Test
    fun aRadioMessageWaitsForTheRadioMessageBeforeItWhateverWentOverBluetoothInBetween() = runTest {
        val pair = RadioPair().established()
        fun offers() = pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage }
        // The first is inside the radio and stays there.
        val gate = CompletableDeferred<Unit>()
        pair.radio.holdPrivateMessages = gate
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        eventually("the first to be with the radio") { offers() == 1 }

        // The mesh has b for a moment: a message goes over Bluetooth, behind nothing.
        pair.b.announceTo(pair.a)
        assertTrue(pair.a.service.sendPrivateMessage("in between", pair.b.id, "b", "2".repeat(36)))
        assertNotNull(pair.a.connection.awaitEncryptedFrom(pair.a.id))
        pair.b.leave(pair.a)

        // Over the radio again: behind the first, which is still there.
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "3".repeat(36)))
        never("the second to be offered while the first is with the radio", forMillis = 300) { offers() != 1 }
        gate.complete(Unit)
        eventually("both to arrive") { pair.b.delegate.messages.size == 2 }
        assertEquals(listOf("first", "second"), pair.b.delegate.messages.map { it.second })
    }

    @Test
    fun noMoreThanSixteenMessagesWaitForTheRadioAndEachIsGivenUpByItsOwnClock() = runTest {
        val pair = RadioPair(radioRetryMs = 20, radioGiveUpMs = 500).established()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage) RadioSendResult.NO_TIME_ON_AIR else RadioSendResult.SENT
        }
        val started = TimeSource.Monotonic.markNow()
        repeat(17) { index ->
            assertTrue(pair.a.service.sendPrivateMessage("message $index", pair.b.id, "b", "id-$index".padEnd(36, '0')))
        }

        // The seventeenth has no place to wait and is told so at once...
        eventually("the one too many to be refused") { pair.a.delegate.failures.isNotEmpty() }
        assertEquals("id-16".padEnd(36, '0') to "too many messages are waiting for the radio", pair.a.delegate.failures.first())
        // ...and the sixteen are each given up 500 ms after they were handed over: together, not one
        // after another (that would take eight seconds).
        eventually("the sixteen to be given up") { pair.a.delegate.failures.size == 17 }
        assertTrue(started.elapsedNow() < 4.seconds, "took ${started.elapsedNow()}")
        assertEquals(List(16) { "no time on air for it" }, pair.a.delegate.failures.drop(1).map { it.second })
        assertTrue(pair.b.delegate.messages.isEmpty())
    }

    @Test
    fun aWaitingRadioMessageWhosePeerIsNoLongerHeardIsReportedAndNotHandedToTheBluetoothLinks() = runTest {
        val pair = RadioPair(radioRetryMs = 20).established()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage) RadioSendResult.NO_TIME_ON_AIR else RadioSendResult.SENT
        }
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("it to be offered to the radio") { pair.radio.sent.any { it.purpose == RadioPurpose.PrivateMessage } }

        // b's heartbeat runs out. The mesh never had b: the links there are, which would take the
        // write, are not b's, and the message is not left standing as sent on their word.
        pair.radio.unheard += pair.b.id
        eventually("the message to be reported as not sent") {
            pair.a.delegate.failures.toList() == listOf(messageId to BluetoothMeshService.OUT_OF_REACH)
        }
        assertEquals(0, pair.a.connection.broadcastAttempts)
    }

    @Test
    fun aWaitingRadioMessageThatNoLinkTakesOnceTheMeshHasThePeerIsReportedAsFailed() = runTest {
        val pair = RadioPair(radioRetryMs = 20).established()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage) RadioSendResult.NO_TIME_ON_AIR else RadioSendResult.SENT
        }
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("it to be offered to the radio") { pair.radio.sent.any { it.purpose == RadioPurpose.PrivateMessage } }

        pair.a.connection.deliverPackets = false
        pair.b.announceTo(pair.a)
        eventually("the message to be reported as not sent") {
            pair.a.delegate.failures.toList() == listOf(messageId to "no link carried it")
        }
    }

    @Test
    fun aBluetoothWriteThatNeverAnswersHoldsUpNothingThatWaitsForTheRadio() = runTest {
        val pair = RadioPair(radioRetryMs = 20).established()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage) RadioSendResult.NO_TIME_ON_AIR else RadioSendResult.SENT
        }
        fun offers() = pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage }
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))
        eventually("the first to be offered to the radio") { offers() >= 1 }

        // The mesh has b now, so both leave for the Bluetooth links, whose write never answers.
        pair.a.connection.holdEveryBroadcast = CompletableDeferred()
        pair.b.announceTo(pair.a)
        val offered = offers()
        never("another offer to the radio while the mesh has the peer", forMillis = 200) { offers() != offered }

        // b is on the radio only again: a third message is not stuck behind those two.
        pair.b.leave(pair.a)
        pair.radio.answer = { RadioSendResult.SENT }
        assertTrue(pair.a.service.sendPrivateMessage("third", pair.b.id, "b", "3".repeat(36)))
        eventually("the third to arrive over the radio") { pair.b.delegate.messages.map { it.second } == listOf("third") }
    }

    @Test
    fun aRadioMessageDoesNotWaitBehindAnotherLongerThanItMayWaitAtAll() = runTest {
        val pair = RadioPair(radioGiveUpMs = 300).established()
        // The first goes into the radio and never comes out.
        pair.radio.holdPrivateMessages = CompletableDeferred()
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))

        eventually("the second to be given up") {
            pair.a.delegate.failures.toList() == listOf("2".repeat(36) to "no time on air for it")
        }
        assertEquals(1, pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage })
    }

    @Test
    fun aRadioMessageStaysBehindEveryEarlierOneThatHasNotEndedWhenThoseBetweenThemGaveUp() = runTest {
        val pair = RadioPair(radioGiveUpMs = 1_000).established()
        fun offers() = pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage }
        val gaveUp = "no time on air for it"
        // The first is inside the radio and stays there.
        val gate = CompletableDeferred<Unit>()
        pair.radio.holdPrivateMessages = gate
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        eventually("the first to be with the radio") { offers() == 1 }

        // The second waits behind it, and the third, handed over a little later, behind both: when
        // the second gives up, the third still has the first ahead of it and gives up in its turn.
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))
        withContext(Dispatchers.Default) { delay(300) }
        assertTrue(pair.a.service.sendPrivateMessage("third", pair.b.id, "b", "3".repeat(36)))
        eventually("the second and the third to be given up, or one of them to be offered") {
            pair.a.delegate.failures.size == 2 || offers() != 1
        }
        assertEquals(1, offers())
        assertEquals(listOf("2".repeat(36) to gaveUp, "3".repeat(36) to gaveUp), pair.a.delegate.failures.toList())

        // So has the fourth, though all it followed are gone.
        assertTrue(pair.a.service.sendPrivateMessage("fourth", pair.b.id, "b", "4".repeat(36)))
        never("the fourth to be offered while the first is with the radio", forMillis = 250) { offers() != 1 }
        gate.complete(Unit)
        eventually("the first and the fourth to arrive") { pair.b.delegate.messages.size == 2 }
        assertEquals(listOf("first", "fourth"), pair.b.delegate.messages.map { it.second })
    }

    @Test
    fun theTimeAMessageSpentBehindAnotherCountsTowardsTheTimeItMayWait() = runTest {
        val pair = RadioPair(radioRetryMs = 50, radioGiveUpMs = 2_000).established()
        // The first message goes into the radio and stays there for a while; whatever follows it
        // gets no time on air.
        val first = AtomicReference<ByteArray?>(null)
        pair.radio.answer = { sent ->
            when {
                sent.purpose != RadioPurpose.PrivateMessage -> RadioSendResult.SENT
                first.compareAndSet(null, sent.bytes) || first.get() === sent.bytes -> RadioSendResult.SENT
                else -> RadioSendResult.NO_TIME_ON_AIR
            }
        }
        val gate = CompletableDeferred<Unit>()
        pair.radio.holdPrivateMessages = gate
        fun offersOfTheSecond() = pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage && it.bytes !== first.get() }
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        eventually("the first to be with the radio") { first.get() != null }
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))

        // Three quarters of what the second may wait are gone before its turn comes.
        withContext(Dispatchers.Default) { delay(1_500) }
        assertEquals(0, offersOfTheSecond())
        gate.complete(Unit)
        eventually("the second to be given up") {
            pair.a.delegate.failures.toList() == listOf("2".repeat(36) to "no time on air for it")
        }
        // The quarter that was left is some ten offers; a clock started at its turn would have
        // allowed forty.
        val offers = offersOfTheSecond()
        assertTrue(offers in 1..24, "offered $offers times")
    }

    @Test
    fun aRadioMessageDoesNotWaitForOneToSomebodyElse() = runTest {
        val pair = RadioPair().established()
        val c = RadioDevice("3", BluetoothMeshService.RADIO_RETRY_MS, BluetoothMeshService.RADIO_GIVE_UP_MS)
        pair.radio.join(c.service)
        pair.a.service.initiateNoiseHandshake(c.id)
        eventually("a session with the third device") {
            pair.a.service.hasEstablishedSession(c.id) && c.service.hasEstablishedSession(pair.a.id)
        }
        // What is for b goes into the radio and stays there.
        pair.radio.holdPrivateMessages = CompletableDeferred()
        pair.radio.holdOnlyMessagesTo = pair.b.id
        assertTrue(pair.a.service.sendPrivateMessage("for b", pair.b.id, "b", "1".repeat(36)))
        eventually("it to be with the radio") { pair.radio.sent.any { it.purpose == RadioPurpose.PrivateMessage } }

        assertTrue(pair.a.service.sendPrivateMessage("for c", c.id, "c", "2".repeat(36)))
        eventually("the message for the other peer to arrive") { c.delegate.messages.map { it.second } == listOf("for c") }
    }

    @Test
    fun aMessageThatHasLeftNoLongerTakesAPlaceAmongThoseWaiting() = runTest {
        val pair = RadioPair().established()
        repeat(20) { index ->
            assertTrue(pair.a.service.sendPrivateMessage("message $index", pair.b.id, "b", "id-$index".padEnd(36, '0')))
            eventually("message $index to arrive or be refused") {
                pair.b.delegate.messages.size == index + 1 || pair.a.delegate.failures.isNotEmpty()
            }
            assertTrue(pair.a.delegate.failures.isEmpty(), "refused: ${pair.a.delegate.failures}")
        }
    }

    @Test
    fun aMessageWhosePeerTheRadioStopsHearingBeforeItIsEncryptedIsReportedAndUsesNoNumberOfTheSession() = runTest {
        // Handed over because the radio heard the peer, which the mesh never had: by the time the
        // message is to be encrypted (the second look) the radio hears it no longer.
        val pair = RadioPair().established()
        pair.radio.unheardFromLook(2, pair.a, pair.b)
        assertTrue(pair.a.service.sendPrivateMessage("lost", pair.b.id, "b", messageId))
        eventually("the message to be reported as not sent") {
            pair.a.delegate.failures.toList() == listOf(messageId to BluetoothMeshService.OUT_OF_REACH)
        }
        assertEquals(0, pair.a.connection.broadcastAttempts)

        // Heard again: the next message carries the session's first number, the lost one took none.
        pair.radio.stillHeard = { _, _ -> true }
        assertTrue(pair.a.service.sendPrivateMessage("next", pair.b.id, "b", "2".repeat(36)))
        eventually("the next message to be offered") { pair.radio.sent.any { it.purpose == RadioPurpose.PrivateMessage } }
        val offered = pair.radio.sent.single { it.purpose == RadioPurpose.PrivateMessage }
        assertEquals(0L, nonceOf(assertNotNull(BinaryProtocol.decodeExact(offered.bytes))))
    }

    @Test
    fun aMessageWhosePeerTheRadioStopsHearingOnceItIsEncryptedIsReportedAndOfferedToNoLink() = runTest {
        // The third look is the one before the first offer.
        val pair = RadioPair().established()
        pair.radio.unheardFromLook(3, pair.a, pair.b)
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))

        eventually("the message to be reported as not sent") {
            pair.a.delegate.failures.toList() == listOf(messageId to BluetoothMeshService.OUT_OF_REACH)
        }
        assertTrue(pair.radio.sent.none { it.purpose == RadioPurpose.PrivateMessage })
        assertEquals(0, pair.a.connection.broadcastAttempts)
    }

    @Test
    fun aMessageWhoseSessionIsGoneWhenItIsToBeEncryptedIsReportedAsFailed() = runTest {
        val pair = RadioPair().established()
        pair.a.service.clearAllEncryptionData()
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))

        eventually("the message to be reported as not sent") {
            pair.a.delegate.failures.toList() == listOf(messageId to "no session with the peer any more")
        }
        assertTrue(pair.radio.sent.none { it.purpose == RadioPurpose.PrivateMessage })
    }

    @Test
    fun aRadioThatFailsIsReportedForTheMessage() = runTest {
        val pair = RadioPair().established()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage) RadioSendResult.FAILED else RadioSendResult.SENT
        }
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("the failure to be reported") { pair.a.delegate.failures.toList() == listOf(messageId to "the radio failed") }
        assertEquals(1, pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage })
    }

    @Test
    fun aPeerTheRadioDoesNotHearIsNotSentToOverIt() = runTest {
        val pair = RadioPair()
        val stranger = "0f0e0d0c0b0a0908"
        assertFalse(pair.a.service.reachesByRadio(stranger))
        assertEquals(PrivateMessageText.MAX_BYTES, pair.a.service.privateTextLimitFor(stranger))
        pair.a.service.initiateNoiseHandshake(stranger)

        eventually("the handshake to be owed for want of a link") { pair.a.service.handshakesOwedCount() == 1 }
        assertTrue(pair.radio.sent.isEmpty())
    }

    @Test
    fun theOpeningTheUserIsOwedGoesOutWhenTheRadioHearsItsPeerAgainAsTheUsersOwn() = runTest {
        val pair = RadioPair()
        // No Bluetooth link carries anything, and b is not heard when a's user writes to it.
        pair.a.connection.deliverPackets = false
        pair.radio.unheard += pair.b.id
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        pair.awaitOwedAndPutAside()
        assertTrue(pair.radio.sent.isEmpty())
        // A change of what the radio hears that does not bring b back starts nothing, and loses nothing.
        assertEquals(emptyList(), pair.a.service.startOwedOverRadio())

        pair.radio.unheard -= pair.b.id
        pair.a.service.onRadioHearsChanged()
        pair.awaitSessions()

        // The opening the user's action paid for and that could not go then: that one, and no other.
        assertEquals(listOf<RadioPurpose>(RadioPurpose.HandshakeOpening(byUser = true)), pair.openingsOfA())
        eventually("nothing to be owed any more") { pair.a.service.handshakesOwedCount() == 0 }
    }

    @Test
    fun onceTheUsersOpeningHasLeftTheAirNoChangeOfWhatTheRadioHearsSendsAnother() = runTest {
        val pair = RadioPair()
        pair.a.connection.deliverPackets = false
        pair.radio.passOn = { false }
        pair.radio.unheard += pair.b.id
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        pair.awaitOwedAndPutAside()
        pair.radio.unheard -= pair.b.id

        assertEquals(listOf(pair.b.id), pair.a.service.startOwedOverRadio())
        eventually("the opening to have left") { pair.a.service.handshakeAttemptsFor(pair.b.id) == 1 }
        // No answer comes; the sweeper puts the attempt aside. The handshake is still owed.
        pair.a.service.sweepStalledHandshakes(muchLater())
        assertFalse(pair.a.service.isHandshakeInFlight(pair.b.id))
        assertEquals(1, pair.a.service.handshakesOwedCount())

        assertEquals(emptyList(), pair.a.service.startOwedOverRadio())
        assertEquals(1, pair.openingsOfA().size)
    }

    @Test
    fun anOpeningABluetoothLinkTookForAPeerTheMeshDoesNotHaveIsStillOwedOverTheRadioOnceThatAttemptIsOver() = runTest {
        val pair = RadioPair()
        // Some Bluetooth link takes the opening; it does not lead to b, which is not heard either.
        pair.radio.unheard += pair.b.id
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        eventually("the opening to be counted as sent") { pair.a.service.handshakeAttemptsFor(pair.b.id) == 1 }
        assertTrue(pair.a.service.isHandshakeInFlight(pair.b.id))
        pair.radio.unheard -= pair.b.id

        // Nothing is started, and nothing is used up, while that attempt is in flight.
        assertEquals(emptyList(), pair.a.service.startOwedOverRadio())

        pair.a.service.sweepStalledHandshakes(muchLater())
        assertFalse(pair.a.service.isHandshakeInFlight(pair.b.id))
        assertEquals(listOf(pair.b.id), pair.a.service.startOwedOverRadio())
        pair.awaitSessions()
        assertEquals(listOf<RadioPurpose>(RadioPurpose.HandshakeOpening(byUser = true)), pair.openingsOfA())
    }

    @Test
    fun anOpeningTheRadioHasNoTimeOnAirForIsStillOwedAndGoesOutAtALaterChange() = runTest {
        val pair = RadioPair()
        pair.a.connection.deliverPackets = false
        pair.radio.answer = { RadioSendResult.NO_TIME_ON_AIR }
        // Heard, and refused when the user asks.
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        pair.awaitOwedAndPutAside()
        eventually("the opening to have been offered") { pair.openingsOfA().size == 1 }

        // Refused again when the radio hears something next.
        assertEquals(listOf(pair.b.id), pair.a.service.startOwedOverRadio())
        eventually("the opening to have been offered again") { pair.openingsOfA().size == 2 }
        eventually("the refused attempt to be put aside") { !pair.a.service.isHandshakeInFlight(pair.b.id) }

        pair.radio.answer = { RadioSendResult.SENT }
        assertEquals(listOf(pair.b.id), pair.a.service.startOwedOverRadio())
        pair.awaitSessions()
        assertEquals(emptyList(), pair.a.service.startOwedOverRadio())
        assertEquals(List(3) { RadioPurpose.HandshakeOpening(byUser = true) }, pair.openingsOfA())
    }

    @Test
    fun anActionOfTheUsersWhileAHandshakeItDidNotPayForIsInFlightIsOwedItsOpening() = runTest {
        val pair = RadioPair().established()
        // b receives nothing from here on. Three packets under b's id that a cannot read: a gives the
        // session up and opens again by itself.
        pair.radio.passOn = { false }
        repeat(3) { round ->
            pair.a.service.onLoRaPacketReceived(
                exactPacket(pair.b.id, pair.a.id, MessageType.NOISE_ENCRYPTED, ByteArray(40) { (it + round).toByte() })
            )
        }
        eventually("a's own opening to have left") { pair.a.service.automaticHandshakesOwedCount() == 1 }
        assertTrue(pair.a.service.isHandshakeInFlight(pair.b.id))

        // The user writes to b meanwhile: nothing more is sent, the handshake in flight is the user's now.
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        eventually("the handshake to become the user's") {
            pair.a.service.handshakesOwedCount() == 1 && pair.a.service.automaticHandshakesOwedCount() == 0
        }
        // It comes to nothing.
        pair.a.service.sweepStalledHandshakes(muchLater())
        assertFalse(pair.a.service.isHandshakeInFlight(pair.b.id))

        // What the user's action paid for has not left yet: it does now.
        assertEquals(listOf(pair.b.id), pair.a.service.startOwedOverRadio())
        eventually("the user's opening to have left") { pair.a.service.handshakeAttemptsFor(pair.b.id) == 2 }
        // Once: when that one comes to nothing too, there is no other.
        pair.a.service.sweepStalledHandshakes(muchLater())
        assertFalse(pair.a.service.isHandshakeInFlight(pair.b.id))
        assertEquals(emptyList(), pair.a.service.startOwedOverRadio())
        // The first of the three is the one the pair was established with.
        assertEquals(
            listOf(true, false, true),
            pair.openingsOfA().map { (it as RadioPurpose.HandshakeOpening).byUser },
        )
    }

    @Test
    fun aRestartBroughtAboutByAForgedPacketLeavesNoOpeningOfTheUsersToCome() = runTest {
        val pair = RadioPair()
        // b never hears a's opening, so the user's handshake stays in flight: its opening has left the air.
        pair.radio.passOn = { false }
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        eventually("the user's opening to be recorded") { pair.a.service.handshakeAttemptsFor(pair.b.id) == 1 }
        withContext(Dispatchers.Default) { delay(HandshakeRefreshPolicy.HANDSHAKE_RESTART_GRACE_MS + 300) }

        // Any packet under b's id from an address a has not seen b on makes a start the handshake
        // again; this time the radio has no time on air for it.
        pair.radio.answer = { RadioSendResult.NO_TIME_ON_AIR }
        val forged = BitchatPacket(
            type = MessageType.NOISE_ENCRYPTED.value, senderID = pair.b.id.hexToBytes(),
            recipientID = "0f0e0d0c0b0a0908".hexToBytes(), timestamp = 7u, payload = ByteArray(40) { it.toByte() }, ttl = 1u,
        )
        pair.a.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(forged)), "an-address-never-seen")
        eventually("the restart to be refused and put aside") {
            pair.openingsOfA().size == 2 && !pair.a.service.isHandshakeInFlight(pair.b.id)
        }
        assertEquals(1, pair.a.service.handshakesOwedCount())

        // Owed to the user still, heard, nothing in flight: and nothing of the user's to send for it.
        pair.radio.answer = { RadioSendResult.SENT }
        assertEquals(emptyList(), pair.a.service.startOwedOverRadio())
        assertEquals(
            listOf<RadioPurpose>(RadioPurpose.HandshakeOpening(byUser = true), RadioPurpose.HandshakeOpening(byUser = false)),
            pair.openingsOfA(),
        )
    }

    @Test
    fun anOpeningOwedToAPeerTheMeshHasIsNotSentBecauseTheRadioHearsThatPeerToo() = runTest {
        val pair = RadioPair()
        pair.a.connection.deliverPackets = false
        pair.b.announceTo(pair.a)
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        pair.awaitOwedAndPutAside()

        // Heard on the radio, owed to the user, nothing in flight: left to the Bluetooth links all the
        // same, which is where what this device starts for a peer the mesh has goes.
        assertEquals(emptyList(), pair.a.service.startOwedOverRadio())
        assertTrue(pair.radio.sent.isEmpty())
    }

    @Test
    fun anOpeningTheRadioRefusesTakesBackOnlyItselfWhenThePeersOwnOpeningArrivedMeanwhile() = runTest {
        thePeersOpeningArrivesBeforeOursIsHandedToALink(oursGoesToTheRadio = true)
    }

    @Test
    fun anOpeningNoBluetoothLinkTakesTakesBackOnlyItselfWhenThePeersOwnOpeningArrivedMeanwhile() = runTest {
        thePeersOpeningArrivesBeforeOursIsHandedToALink(oursGoesToTheRadio = false)
    }

    /**
     * One device is owed an opening by its user, and a change of what the radio hears sends it.
     * Between the moment that handshake is begun and the moment its opening is handed to a link,
     * the peer's own opening arrives; this device, whose id is the larger, gives way and answers.
     * Its own opening then does not go out. The answer it gave is not its opening: the peer's last
     * message must still find it.
     */
    private suspend fun thePeersOpeningArrivesBeforeOursIsHandedToALink(oursGoesToTheRadio: Boolean) {
        val pair = RadioPair()
        val (yielding, holding) = if (pair.a.id > pair.b.id) pair.a to pair.b else pair.b to pair.a
        fun sentBy(device: RadioDevice, isIt: (RadioPurpose) -> Boolean) =
            pair.radio.sent.firstOrNull { it.from == device.id && isIt(it.purpose) }

        yielding.connection.deliverPackets = false
        pair.radio.unheard += holding.id
        yielding.service.initiateNoiseHandshake(holding.id)
        eventually("the opening to be owed") {
            yielding.service.handshakesOwedCount() == 1 && !yielding.service.isHandshakeInFlight(holding.id)
        }
        pair.radio.unheard -= holding.id

        // The peer opens too; its opening is in the air, and so is everything else for now.
        pair.radio.passOn = { false }
        holding.service.initiateNoiseHandshake(yielding.id)
        eventually("the peer's opening") { sentBy(holding) { it is RadioPurpose.HandshakeOpening } != null }
        val theirOpening = requireNotNull(sentBy(holding) { it is RadioPurpose.HandshakeOpening })

        // From here on answers arrive; the peer's last message is held back until ours is known not to have gone.
        pair.radio.passOn = { it.purpose != RadioPurpose.HandshakeFinal }
        val refused = CompletableDeferred<Unit>()
        pair.radio.answer = { sent ->
            if (sent.from == yielding.id && sent.purpose is RadioPurpose.HandshakeOpening) {
                refused.complete(Unit)
                RadioSendResult.NO_TIME_ON_AIR
            } else {
                RadioSendResult.SENT
            }
        }
        // The first look at whether the radio hears the peer is this rule's; the second is made when
        // the handshake has been begun and its opening is about to be handed to a link.
        val looks = AtomicInteger()
        pair.radio.stillHeard = { owner, peerID ->
            if (owner == yielding.id && peerID == holding.id && looks.incrementAndGet() == 2) {
                yielding.service.onLoRaPacketReceived(theirOpening.bytes)
                runBlocking {
                    eventually("the answer to have reached the peer") { sentBy(holding) { it == RadioPurpose.HandshakeFinal } != null }
                }
                oursGoesToTheRadio
            } else {
                true
            }
        }

        assertEquals(listOf(holding.id), yielding.service.startOwedOverRadio())
        eventually("the peer's last message to be in the air") { sentBy(holding) { it == RadioPurpose.HandshakeFinal } != null }
        if (oursGoesToTheRadio) refused.await()
        // Time for what follows an opening that did not go out.
        withContext(Dispatchers.Default) { delay(300) }
        assertTrue(yielding.service.isHandshakeInFlight(holding.id), "the answer to the peer's opening was taken back")

        yielding.service.onLoRaPacketReceived(requireNotNull(sentBy(holding) { it == RadioPurpose.HandshakeFinal }).bytes)
        eventually("a session on both sides") {
            yielding.service.hasEstablishedSession(holding.id) && holding.service.hasEstablishedSession(yielding.id)
        }
    }

    @Test
    fun aMessageRefusedForWantOfTimeOnAirIsOfferedAgainUnchangedAndTheNextOneWaitsBehindIt() = runTest {
        val pair = RadioPair(radioRetryMs = 20).established()
        val refusals = AtomicInteger()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage && refusals.getAndIncrement() < 2) {
                RadioSendResult.NO_TIME_ON_AIR
            } else {
                RadioSendResult.SENT
            }
        }
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))
        eventually("both messages to arrive") { pair.b.delegate.messages.size == 2 }

        assertEquals(listOf("first", "second"), pair.b.delegate.messages.map { it.second })
        val offers = pair.radio.sent.filter { it.purpose == RadioPurpose.PrivateMessage }.map { it.bytes }
        assertEquals(4, offers.size)
        assertContentEquals(offers[0], offers[1])
        assertContentEquals(offers[0], offers[2])
        assertFalse(offers[2].contentEquals(offers[3]))
    }

    @Test
    fun aMessageThatNeverGetsTimeOnAirIsGivenUpAfterTheBoundAndReportedAsFailed() = runTest {
        val pair = RadioPair(radioRetryMs = 20, radioGiveUpMs = 200).established()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage) RadioSendResult.NO_TIME_ON_AIR else RadioSendResult.SENT
        }
        assertTrue(pair.a.service.sendPrivateMessage("never", pair.b.id, "b", messageId))
        fun offers() = pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage }
        eventually("it to be offered more than once") { offers() >= 2 }
        eventually("it to be given up") { pair.a.delegate.failures.toList() == listOf(messageId to "no time on air for it") }
        val offered = offers()
        never("it to be offered after it was given up", forMillis = 300) { offers() != offered }
        assertTrue(pair.b.delegate.messages.isEmpty())
    }

    @Test
    fun theTimeAMessageMayWaitIsMeasuredByAClockThatOnlyGoesForward() = runTest {
        // Not by the time of day, which can be set back while a message waits: the three minutes are
        // three minutes of this clock, which a test moves by hand.
        val clock = SteppedTimeSource()
        val pair = RadioPair(radioRetryMs = 20, radioWaitClock = clock).established()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.PrivateMessage) RadioSendResult.NO_TIME_ON_AIR else RadioSendResult.SENT
        }
        fun offers() = pair.radio.sent.count { it.purpose == RadioPurpose.PrivateMessage }
        assertTrue(pair.a.service.sendPrivateMessage("waiting", pair.b.id, "b", messageId))
        eventually("it to be offered") { offers() >= 1 }

        // A second short of the bound it is still offered, however long that takes by any other clock...
        clock.advance(BluetoothMeshService.RADIO_GIVE_UP_MS.milliseconds - 1.seconds)
        val offered = offers()
        eventually("it to be offered again") { offers() >= offered + 3 }
        assertTrue(pair.a.delegate.failures.isEmpty())
        // ...and at the bound it is given up at its next offer.
        clock.advance(1.seconds)
        eventually("it to be given up") { pair.a.delegate.failures.toList() == listOf(messageId to "no time on air for it") }
    }

    @Test
    fun anOpeningRefusedForWantOfTimeOnAirLeavesTheHandshakeOwedAndCountsNoAttempt() = runTest {
        val pair = RadioPair()
        pair.radio.answer = { RadioSendResult.NO_TIME_ON_AIR }
        pair.a.service.initiateNoiseHandshake(pair.b.id)

        eventually("the handshake to be owed") { pair.a.service.handshakesOwedCount() == 1 }
        eventually("the candidate to be given up") { !pair.a.service.isHandshakeInFlight(pair.b.id) }
        assertEquals(0, pair.a.service.handshakeSupervisorSize())
        assertFalse(pair.b.service.hasNoiseCandidate(pair.a.id))
    }

    @Test
    fun aTextTooLongForOneFrameIsRefusedBeforeAnythingIsEncrypted() = runTest {
        val pair = RadioPair().established()
        assertEquals(141, pair.a.service.privateTextLimitFor(pair.b.id))
        fun privatePackets() = pair.radio.sent.filter { it.purpose == RadioPurpose.PrivateMessage }
            .map { assertNotNull(BinaryProtocol.decodeExact(it.bytes)) }

        assertTrue(pair.a.service.sendPrivateMessage("before", pair.b.id, "b", messageId))
        eventually("the first message") { privatePackets().size == 1 }
        assertFalse(pair.a.service.sendPrivateMessage("x".repeat(142), pair.b.id, "b", messageId))
        // The longest text that fits fills the frame to its last byte...
        assertTrue(pair.a.service.sendPrivateMessage("x".repeat(141), pair.b.id, "b", messageId))
        eventually("the second message") { privatePackets().size == 2 }
        assertEquals(MAX_LORA_PACKET_BYTES, pair.radio.sent.last { it.purpose == RadioPurpose.PrivateMessage }.bytes.size)
        // ...and carries the very next number of the session: the refused one took none.
        val (first, second) = privatePackets()
        assertEquals(nonceOf(first) + 1, nonceOf(second))
        eventually("both to arrive") { pair.b.delegate.messages.size == 2 }
    }

    @Test
    fun aRecoveryCausedByWhatTheRadioBroughtIsNeverAnOpeningOfTheUsers() = runTest {
        val pair = RadioPair().established()
        // Three packets under b's id that a cannot read: a gives the session up and opens again.
        repeat(3) { round ->
            pair.a.service.onLoRaPacketReceived(
                exactPacket(pair.b.id, pair.a.id, MessageType.NOISE_ENCRYPTED, ByteArray(40) { (it + round).toByte() })
            )
        }
        eventually("a to open a handshake by itself") {
            pair.radio.sent.any { it.from == pair.a.id && it.purpose == RadioPurpose.HandshakeOpening(byUser = false) }
        }
        assertEquals(
            1,
            pair.radio.sent.count { it.from == pair.a.id && it.purpose == RadioPurpose.HandshakeOpening(byUser = true) },
        )
    }

    @Test
    fun aHandshakeAnsweredBeforeItsOpeningIsReportedSentLeavesNothingOwed() = runTest {
        // Over the radio the opening is reported sent only once it has left the air, and by then
        // the other side may have answered and the session may stand.
        val pair = RadioPair()
        pair.radio.holdOpeningsUntilAnswered = true
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        pair.awaitSessions()

        eventually("the owed record to go") { pair.a.service.handshakesOwedCount() == 0 }
        never("an owed record or an attempt to come back", forMillis = 400) {
            pair.a.service.handshakesOwedCount() != 0 || pair.a.service.automaticHandshakesOwedCount() != 0 ||
                pair.a.service.handshakeSupervisorSize() != 0
        }
    }

    @Test
    fun aFileIsNeverSentOverTheRadio() = runTest {
        val pair = RadioPair().established()
        val before = pair.radio.sent.size
        // And the caller is told that nothing was started for it.
        assertFalse(pair.a.service.sendFilePrivate(pair.b.id, BitchatFilePacket("note.txt", 3, "text/plain", byteArrayOf(1, 2, 3))))

        never("a file on the radio", forMillis = 300) { pair.radio.sent.size != before }
        assertEquals(0, pair.a.connection.broadcastAttempts)
    }

    private fun RadioPair.acknowledgements() = radio.sent.filter { it.purpose == RadioPurpose.DeliveryAck }

    /** b comes to distrust its session with a and makes a new one, over the radio; both sides then have the new one. */
    private suspend fun RadioPair.makeTheSessionAgainOverTheRadio() {
        val before = radio.sent.count { it.purpose is RadioPurpose.HandshakeOpening }
        repeat(3) { round ->
            b.service.onLoRaPacketReceived(
                exactPacket(a.id, b.id, MessageType.NOISE_ENCRYPTED, ByteArray(40) { (it + round).toByte() })
            )
        }
        eventually("b to open a handshake again") { radio.sent.count { it.purpose is RadioPurpose.HandshakeOpening } > before }
        eventually("the last message of the new handshake to have gone") {
            radio.sent.count { it.purpose == RadioPurpose.HandshakeFinal } >= 2
        }
        awaitSessions()
    }

    @Test
    fun aTextThatArrivedOverTheRadioIsAcknowledgedAndItsSenderIsToldOnce() = runTest {
        val pair = RadioPair().established()
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))

        eventually("the sender to be told") { pair.a.delegate.delivered.toList() == listOf(messageId to pair.b.id) }
        val acknowledgement = pair.acknowledgements().single()
        assertEquals(pair.b.id, acknowledgement.from)
        assertEquals(pair.a.id, acknowledgement.to)
        assertRadioForm(acknowledgement, MessageType.NOISE_ENCRYPTED)
        // One number: 57 bytes of frame less the frame's own five, and four for the number.
        assertEquals(56, acknowledgement.bytes.size)
        never("a second acknowledgement or a second word to the sender", forMillis = 400) {
            pair.acknowledgements().size != 1 || pair.a.delegate.delivered.size != 1
        }
        assertTrue(pair.a.delegate.failures.isEmpty())
    }

    @Test
    fun textsThatArriveWhileTheFirstWaitsShareOneAcknowledgement() = runTest {
        val pair = RadioPair(ackGatherMs = 2_000).established()
        val ids = List(3) { "id-$it".padEnd(36, '0') }
        ids.forEachIndexed { index, id -> assertTrue(pair.a.service.sendPrivateMessage("text $index", pair.b.id, "b", id)) }
        eventually("all three to arrive") { pair.b.delegate.messages.size == 3 }
        assertTrue(pair.acknowledgements().isEmpty(), "acknowledged before the others could join")

        eventually("the sender to be told of all three") { pair.a.delegate.delivered.size == 3 }
        assertEquals(ids.map { it to pair.b.id }.toSet(), pair.a.delegate.delivered.toSet())
        assertEquals(1, pair.acknowledgements().size)
        // Three numbers.
        assertEquals(64, pair.acknowledgements().single().bytes.size)
    }

    @Test
    fun ofNineTextsThatArriveBeforeTheAcknowledgementGoesTheNewestEightAreAcknowledged() = runTest {
        val pair = RadioPair(ackGatherMs = 2_500).established()
        val ids = List(9) { "id-$it".padEnd(36, '0') }
        ids.forEachIndexed { index, id -> assertTrue(pair.a.service.sendPrivateMessage("text $index", pair.b.id, "b", id)) }
        eventually("all nine to arrive") { pair.b.delegate.messages.size == 9 }
        assertTrue(pair.acknowledgements().isEmpty(), "acknowledged before the others could join")

        eventually("the sender to be told of eight") { pair.a.delegate.delivered.size == 8 }
        assertEquals(ids.drop(1).map { it to pair.b.id }.toSet(), pair.a.delegate.delivered.toSet())
        never("the first to be acknowledged after all", forMillis = 400) { pair.a.delegate.delivered.size != 8 }
        assertEquals(1, pair.acknowledgements().size)
    }

    @Test
    fun anAcknowledgementRefusedForWantOfTimeOnAirIsOfferedAgainWithWhatHasArrivedSince() = runTest {
        val pair = RadioPair(ackGatherMs = 100, ackRetryMs = 400).established()
        val refusals = AtomicInteger()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.DeliveryAck && refusals.getAndIncrement() < 2) RadioSendResult.NO_TIME_ON_AIR else RadioSendResult.SENT
        }
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        eventually("the acknowledgement to be offered and refused") { pair.acknowledgements().isNotEmpty() }
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))

        eventually("the sender to be told of both") { pair.a.delegate.delivered.size == 2 }
        assertEquals(setOf("1".repeat(36) to pair.b.id, "2".repeat(36) to pair.b.id), pair.a.delegate.delivered.toSet())
        // Two refused, and the one that went carried both numbers.
        assertEquals(listOf(56, 60, 60), pair.acknowledgements().map { it.bytes.size })
    }

    @Test
    fun aTextIsNotAcknowledgedOnceItArrivedTooLongAgo() = runTest {
        val clock = SteppedTimeSource()
        val pair = RadioPair(radioWaitClock = clock, ackGatherMs = 600).established()
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("it to arrive") { pair.b.delegate.messages.size == 1 }
        assertTrue(pair.acknowledgements().isEmpty())

        clock.advance(BluetoothMeshService.ACK_MAX_AGE_MS.milliseconds + 1.milliseconds)
        never("an acknowledgement", forMillis = 1_200) { pair.acknowledgements().isNotEmpty() }
        assertTrue(pair.a.delegate.delivered.isEmpty())
    }

    @Test
    fun aTextReadByTheSessionThatWasReplacedIsNotAcknowledged() = runTest {
        val pair = RadioPair().established()
        // The text is inside the radio, encrypted in the first session, while the two make a new one.
        val gate = CompletableDeferred<Unit>()
        pair.radio.holdPrivateMessages = gate
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("the text to be with the radio") { pair.radio.sent.any { it.purpose == RadioPurpose.PrivateMessage } }
        pair.makeTheSessionAgainOverTheRadio()

        gate.complete(Unit)
        eventually("the text to arrive all the same") { pair.b.delegate.messages.map { it.second } == listOf("hello") }
        never("an acknowledgement", forMillis = 500) { pair.acknowledgements().isNotEmpty() }
        assertTrue(pair.a.delegate.delivered.isEmpty())
        eventually("the sender of acknowledgements to have ended") { pair.b.service.acknowledgersRunning() == 0 }
    }

    @Test
    fun whatWasNotedInOneSessionIsNeverAcknowledgedInTheNextNorTakenForItsMessages() = runTest {
        val pair = RadioPair(ackGatherMs = 2_500).established()
        val old = "1".repeat(36)
        val new = "2".repeat(36)
        // The first text of the first session arrives and waits to be acknowledged...
        assertTrue(pair.a.service.sendPrivateMessage("old", pair.b.id, "b", old))
        eventually("it to arrive") { pair.b.delegate.messages.size == 1 }
        // ...when the session is made again. The first text of the new session has the same number.
        pair.makeTheSessionAgainOverTheRadio()
        assertTrue(pair.a.service.sendPrivateMessage("new", pair.b.id, "b", new))

        eventually("the sender to be told of the new one") { pair.a.delegate.delivered.isNotEmpty() }
        never("the old one to be confirmed", forMillis = 3_000) { pair.a.delegate.delivered.toList() != listOf(new to pair.b.id) }
        // One frame, with one number: the old session's number was not carried over.
        assertEquals(listOf(56), pair.acknowledgements().map { it.bytes.size })
    }

    @Test
    fun numbersNotedInASessionAreNotAcknowledgedInTheOneThatReplacedIt() = runTest {
        val pair = RadioPair(ackGatherMs = 2_500).established()
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("it to arrive") { pair.b.delegate.messages.size == 1 }
        // The session is made again while the text still waits to be acknowledged, and nothing follows it.
        pair.makeTheSessionAgainOverTheRadio()
        assertTrue(pair.acknowledgements().isEmpty(), "acknowledged before the session was made again")

        never("an acknowledgement in the new session", forMillis = 3_500) { pair.acknowledgements().isNotEmpty() }
        assertTrue(pair.a.delegate.delivered.isEmpty())
        eventually("the sender of acknowledgements to have ended") { pair.b.service.acknowledgersRunning() == 0 }
    }

    @Test
    fun aTextThatCameOverBluetoothInASessionMadeOverTheRadioIsNotAcknowledged() = runTest {
        val pair = RadioPair().established()
        // a's mesh comes to have b, so a writes over Bluetooth; b still has a only on the radio.
        pair.b.announceTo(pair.a)
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        val overBluetooth = pair.a.connection.awaitEncryptedFrom(pair.a.id)
        pair.b.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(overBluetooth)), "address-of-${pair.a.id}")
        eventually("it to arrive") { pair.b.delegate.messages.map { it.second } == listOf("hello") }
        assertTrue(pair.b.service.reachesByRadio(pair.a.id))

        never("an acknowledgement on the radio", forMillis = 500) { pair.acknowledgements().isNotEmpty() }
        assertEquals(0, pair.b.service.acknowledgersRunning())
    }

    @Test
    fun theSenderOfAcknowledgementsEndsWithItsLastNumberAndALaterTextGetsOneOfItsOwn() = runTest {
        val pair = RadioPair(ackGatherMs = 100, ackRetryMs = 5_000).established()
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        eventually("the sender to be told") { pair.a.delegate.delivered.size == 1 }

        // At once, not after another round of waiting: from here on a number starts a sender anew.
        val ended = withContext(Dispatchers.Default) {
            withTimeoutOrNull(1_500) {
                while (pair.b.service.acknowledgersRunning() != 0) delay(10)
                true
            }
        }
        assertNotNull(ended, "the sender of acknowledgements was still running")
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))
        eventually("the sender to be told of the second") { pair.a.delegate.delivered.size == 2 }
        assertEquals(listOf(56, 56), pair.acknowledgements().map { it.bytes.size })
    }

    @Test
    fun aTextThatArrivesWhileAnAcknowledgementIsOnItsWayIsAcknowledgedByTheSameSenderAfterIt() = runTest {
        val pair = RadioPair().established()
        // The acknowledgement of the first text is inside the radio when the second text arrives.
        val gate = CompletableDeferred<Unit>()
        pair.radio.holdAcknowledgements = gate
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        eventually("the first acknowledgement to be with the radio") { pair.acknowledgements().size == 1 }
        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))
        eventually("the second text to arrive") { pair.b.delegate.messages.size == 2 }
        // The app is told of a text a moment before its number is noted: give that moment its time,
        // so that the number is in the entry while the first frame is still out.
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(1, pair.b.service.acknowledgersRunning())

        // Its number was noted in the entry that has a sender already: that sender must not end with
        // the first frame, or nobody would ever send the second.
        gate.complete(Unit)
        eventually("the sender to be told of both") { pair.a.delegate.delivered.size == 2 }
        assertEquals(listOf("1".repeat(36) to pair.b.id, "2".repeat(36) to pair.b.id), pair.a.delegate.delivered.toList())
        assertEquals(listOf(56, 56), pair.acknowledgements().map { it.bytes.size })
        eventually("the sender of acknowledgements to have ended") { pair.b.service.acknowledgersRunning() == 0 }
    }

    @Test
    fun anAcknowledgementTheRadioFailsOnIsGivenUpAndALaterTextIsAcknowledgedAfresh() = runTest {
        val pair = RadioPair().established()
        val failures = AtomicInteger()
        pair.radio.answer = { sent ->
            if (sent.purpose == RadioPurpose.DeliveryAck && failures.getAndIncrement() < 1) RadioSendResult.FAILED else RadioSendResult.SENT
        }
        assertTrue(pair.a.service.sendPrivateMessage("first", pair.b.id, "b", "1".repeat(36)))
        eventually("the acknowledgement to be offered") { pair.acknowledgements().size == 1 }
        never("it to be offered again", forMillis = 500) { pair.acknowledgements().size != 1 }
        assertEquals(0, pair.b.service.acknowledgersRunning())

        assertTrue(pair.a.service.sendPrivateMessage("second", pair.b.id, "b", "2".repeat(36)))
        eventually("the sender to be told of the second") { pair.a.delegate.delivered.isNotEmpty() }
        assertEquals(listOf("2".repeat(36) to pair.b.id), pair.a.delegate.delivered.toList())
    }

    @Test
    fun anAcknowledgementThatOvertakesTheRadiosReportOfItsTextStillConfirmsIt() = runTest {
        val pair = RadioPair().established()
        val gate = CompletableDeferred<Unit>()
        pair.radio.holdReportOfPrivateMessages = gate
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))

        // The text has been passed on and the sender's own call has not come back yet.
        eventually("the sender to be told") { pair.a.delegate.delivered.toList() == listOf(messageId to pair.b.id) }
        gate.complete(Unit)
    }

    @Test
    fun aTextTheRadioCalledFailedAndThatArrivedIsConfirmedByItsAcknowledgement() = runTest {
        val pair = RadioPair().established()
        pair.radio.failsAfterPassingOn = { it.purpose == RadioPurpose.PrivateMessage }
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))

        eventually("the failure to be reported") { pair.a.delegate.failures.toList() == listOf(messageId to "the radio failed") }
        eventually("and the arrival after it") { pair.a.delegate.delivered.toList() == listOf(messageId to pair.b.id) }
    }

    @Test
    fun anAcknowledgementConfirmsOnlyWhatWasSentToThePeerItComesFrom() = runTest {
        val pair = RadioPair().established()
        val c = RadioDevice("3", BluetoothMeshService.RADIO_RETRY_MS, BluetoothMeshService.RADIO_GIVE_UP_MS)
        pair.radio.join(c.service)
        pair.a.service.initiateNoiseHandshake(c.id)
        eventually("a session with the third device") {
            pair.a.service.hasEstablishedSession(c.id) && c.service.hasEstablishedSession(pair.a.id)
        }
        // The first text to b never arrives; the first text to c has the same number, and c says so.
        pair.radio.holdPrivateMessages = CompletableDeferred()
        pair.radio.holdOnlyMessagesTo = pair.b.id
        assertTrue(pair.a.service.sendPrivateMessage("for b", pair.b.id, "b", "1".repeat(36)))
        assertTrue(pair.a.service.sendPrivateMessage("for c", c.id, "c", "2".repeat(36)))

        eventually("the sender to be told of the one that arrived") { pair.a.delegate.delivered.isNotEmpty() }
        never("the other to be confirmed", forMillis = 400) { pair.a.delegate.delivered.toList() != listOf("2".repeat(36) to c.id) }
    }

    @Test
    fun aTextThatArrivedOverBluetoothIsNotAcknowledgedOnEitherLink() = runTest {
        // The session comes about over Bluetooth, and each has the other on its mesh.
        val pair = RadioPair(joined = false)
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        pair.carryHandshakeOverBluetooth(from = pair.a, to = pair.b)
        pair.carryHandshakeOverBluetooth(from = pair.b, to = pair.a)
        pair.carryHandshakeOverBluetooth(from = pair.a, to = pair.b)
        pair.awaitSessions()
        pair.join()
        pair.b.announceTo(pair.a)
        pair.a.announceTo(pair.b)

        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        val overBluetooth = pair.a.connection.awaitEncryptedFrom(pair.a.id)
        pair.b.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(overBluetooth)), "address-of-${pair.a.id}")
        eventually("it to arrive") { pair.b.delegate.messages.map { it.second } == listOf("hello") }

        never("an acknowledgement on the radio", forMillis = 500) { pair.acknowledgements().isNotEmpty() }
        pair.b.connection.assertNoEncryptedFrom(pair.b.id)
        assertTrue(pair.a.delegate.delivered.isEmpty())
    }

    @Test
    fun anAcknowledgementIsDroppedWhenTheMeshHasThePeerByTheTimeItWouldGo() = runTest {
        val pair = RadioPair(ackGatherMs = 800).established()
        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("it to arrive") { pair.b.delegate.messages.size == 1 }

        pair.a.announceTo(pair.b)
        never("an acknowledgement on the radio", forMillis = 1_500) { pair.acknowledgements().isNotEmpty() }
        pair.b.connection.assertNoEncryptedFrom(pair.b.id)
    }

    @Test
    fun nothingIsAcknowledgedInASessionThatWasNotMadeOverTheRadio() = runTest {
        // For a the session is made over the radio (the answer came that way); the last message of
        // the handshake is lost on the radio and reaches b over Bluetooth, so for b it was not.
        val pair = RadioPair()
        pair.radio.passOn = { it.purpose != RadioPurpose.HandshakeFinal }
        pair.a.service.initiateNoiseHandshake(pair.b.id)
        eventually("the last message to have gone out") { pair.radio.sent.any { it.purpose == RadioPurpose.HandshakeFinal } }
        val last = pair.radio.sent.single { it.purpose == RadioPurpose.HandshakeFinal }
        pair.b.service.onPacketReceived(last.bytes, "address-of-${pair.a.id}")
        pair.awaitSessions()
        assertTrue(pair.a.service.reachesByRadio(pair.b.id))
        assertFalse(pair.b.service.reachesByRadio(pair.a.id))

        assertTrue(pair.a.service.sendPrivateMessage("hello", pair.b.id, "b", messageId))
        eventually("it to arrive") { pair.b.delegate.messages.map { it.second } == listOf("hello") }
        never("an acknowledgement", forMillis = 500) { pair.acknowledgements().isNotEmpty() }
        pair.b.connection.assertNoEncryptedFrom(pair.b.id)
    }

    private fun assertRadioForm(sent: FakeRadio.Sent, type: MessageType) {
        assertTrue(sent.bytes.size <= MAX_LORA_PACKET_BYTES)
        val packet = assertNotNull(BinaryProtocol.decodeExact(sent.bytes), "not the exact form the radio side accepts")
        assertEquals(type.value, packet.type)
        assertEquals(0.toUByte(), packet.ttl)
        assertNull(packet.signature)
        assertContentEquals(sent.from.hexToBytes(), packet.senderID)
        assertContentEquals(sent.to.hexToBytes(), packet.recipientID)
    }
}

private class RadioPair(
    radioRetryMs: Long = BluetoothMeshService.RADIO_RETRY_MS,
    radioGiveUpMs: Long = BluetoothMeshService.RADIO_GIVE_UP_MS,
    joined: Boolean = true,
    radioWaitClock: TimeSource = TimeSource.Monotonic,
    ackGatherMs: Long = 100,
    ackRetryMs: Long = 50,
) {
    val radio = FakeRadio()
    val a = RadioDevice("1", radioRetryMs, radioGiveUpMs, radioWaitClock, ackGatherMs, ackRetryMs)
    val b = RadioDevice("2", radioRetryMs, radioGiveUpMs, radioWaitClock, ackGatherMs, ackRetryMs)

    init {
        if (joined) join()
    }

    /** From now on the two hear each other on the radio. */
    fun join() {
        radio.join(a.service)
        radio.join(b.service)
    }

    /** Takes the next handshake packet [from] put on its Bluetooth links and hands it to [to] as if a link had carried it. */
    suspend fun carryHandshakeOverBluetooth(from: RadioDevice, to: RadioDevice) {
        val packet = from.connection.awaitHandshakeFrom(from.id)
        to.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(packet)), "address-of-${from.id}")
    }

    suspend fun awaitSessions() = eventually("a session on both sides") {
        a.service.hasEstablishedSession(b.id) && b.service.hasEstablishedSession(a.id)
    }

    suspend fun established(): RadioPair {
        a.service.initiateNoiseHandshake(b.id)
        awaitSessions()
        return this
    }

    suspend fun awaitOwedAndPutAside() = eventually("the handshake to be owed and put aside") {
        a.service.handshakesOwedCount() == 1 && !a.service.isHandshakeInFlight(b.id)
    }

    /** What a has offered the radio as the opening of a handshake, oldest first. */
    fun openingsOfA(): List<RadioPurpose> =
        radio.sent.filter { it.from == a.id && it.purpose is RadioPurpose.HandshakeOpening }.map { it.purpose }
}

private class RadioDevice(
    seedDigit: String,
    radioRetryMs: Long,
    radioGiveUpMs: Long,
    radioWaitClock: TimeSource = TimeSource.Monotonic,
    ackGatherMs: Long = 100,
    ackRetryMs: Long = 50,
) {
    val crypto = CryptoSigningFacade(seedDigit.repeat(64))
    val connection = FallbackRecordingConnectionService()
    val delegate = FallbackDelegate()
    val service = BluetoothMeshService(
        scanningService = FallbackNoOpScanningService,
        connectionService = connection,
        gattServerService = FallbackNoOpGattServerService,
        advertisingService = FallbackNoOpAdvertisingService,
        cryptoSigning = crypto,
        radioRetryMs = radioRetryMs,
        radioGiveUpMs = radioGiveUpMs,
        radioWaitClock = radioWaitClock,
        ackGatherMs = ackGatherMs,
        ackRetryMs = ackRetryMs,
    ).also { it.delegate = delegate }
    val id: String get() = service.myPeerID

    /** Makes [other] see this device as a connected mesh peer: its announcement arrives there over Bluetooth. */
    suspend fun announceTo(other: RadioDevice) {
        val payload = requireNotNull(IdentityAnnouncement("peer", crypto.getNoisePublicKey(), crypto.getSigningPublicKey()).encode())
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value, senderID = id.hexToBytes(), recipientID = SpecialRecipients.BROADCAST,
            timestamp = 1u, payload = payload, ttl = 1u,
        )
        other.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(packet)), "address-of-$id")
        eventually("the announced peer to be connected") { other.service.getPeerInfo(id)?.isConnected == true }
    }

    /** Takes this device off [other]'s mesh again: its leave arrives there over Bluetooth. */
    suspend fun leave(other: RadioDevice) {
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value, senderID = id.hexToBytes(), recipientID = SpecialRecipients.BROADCAST,
            timestamp = 2u, payload = byteArrayOf(1), ttl = 1u,
        )
        other.service.onPacketReceived(requireNotNull(BinaryProtocol.encode(packet)), "address-of-$id")
        eventually("the peer to be gone from the mesh") { other.service.getPeerInfo(id)?.isConnected != true }
    }
}

/** The radio between the devices that joined it: records what each hands over and passes it on. */
private class FakeRadio {
    class Sent(val from: String, val to: String, val purpose: RadioPurpose, val bytes: ByteArray)

    val sent = CopyOnWriteArrayList<Sent>()
    private val devices = ConcurrentHashMap<String, BluetoothMeshService>()

    /** What the radio answers; only SENT is passed on to the other device. */
    @Volatile var answer: (Sent) -> RadioSendResult = { RadioSendResult.SENT }

    /** An opening is reported sent only once its sender has a session: the answer overtook the report. */
    @Volatile var holdOpeningsUntilAnswered = false

    /** Whether a frame the radio sent reaches the other device at all. */
    @Volatile var passOn: (Sent) -> Boolean = { true }

    /** Devices that have joined and are no longer heard. */
    val unheard: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Asked on top of that, each time a device looks: whether [owner] still hears [peerID]. */
    @Volatile var stillHeard: (owner: String, peerID: String) -> Boolean = { _, _ -> true }

    /**
     * [device] looks at its way out to [peer] when a private message is handed over (the first look),
     * when it is encrypted (the second) and before every offer to the radio (the third is the first
     * of those). From look number [look] on, [peer] is not heard.
     */
    fun unheardFromLook(look: Int, device: RadioDevice, peer: RadioDevice) {
        val looks = AtomicInteger()
        stillHeard = { owner, peerID -> owner != device.id || peerID != peer.id || looks.incrementAndGet() < look }
    }

    /** While set and not completed, a private message stays inside the radio. */
    @Volatile var holdPrivateMessages: CompletableDeferred<Unit>? = null

    /** When set, only the private messages for this device are held. */
    @Volatile var holdOnlyMessagesTo: String? = null

    /** While set and not completed, a private message that has been passed on is not yet reported as sent. */
    @Volatile var holdReportOfPrivateMessages: CompletableDeferred<Unit>? = null

    /** While set and not completed, an acknowledgement stays inside the radio. */
    @Volatile var holdAcknowledgements: CompletableDeferred<Unit>? = null

    /** Frames that reach the other device although the radio then says it failed. */
    @Volatile var failsAfterPassingOn: (Sent) -> Boolean = { false }

    fun join(service: BluetoothMeshService) {
        val owner = service.myPeerID
        devices[owner] = service
        service.radioLink = object : MeshRadioLink {
            override fun hears(peerID: String) =
                peerID.lowercase() != owner && devices.containsKey(peerID.lowercase()) && peerID.lowercase() !in unheard &&
                    stillHeard(owner, peerID.lowercase())

            override suspend fun send(packet: ByteArray, peerID: String, purpose: RadioPurpose): RadioSendResult {
                val record = Sent(owner, peerID.lowercase(), purpose, packet)
                sent += record
                val result = answer(record)
                if (result != RadioSendResult.SENT) return result
                if (purpose == RadioPurpose.PrivateMessage && (holdOnlyMessagesTo ?: record.to) == record.to) {
                    holdPrivateMessages?.await()
                }
                if (purpose == RadioPurpose.DeliveryAck) holdAcknowledgements?.await()
                if (passOn(record)) devices[record.to]?.onLoRaPacketReceived(packet)
                if (failsAfterPassingOn(record)) return RadioSendResult.FAILED
                if (purpose == RadioPurpose.PrivateMessage) holdReportOfPrivateMessages?.await()
                if (holdOpeningsUntilAnswered && purpose is RadioPurpose.HandshakeOpening) {
                    eventually("the opening to be answered") { service.hasEstablishedSession(record.to) }
                }
                return result
            }
        }
    }
}

class BluetoothMeshServiceFallbackTest {

    @Test
    fun aStalledHandshakeTheOtherSideOpenedIsTakenOverWithTheOrdinaryRetries() = runTest {
        // The opener's last message may have been lost: it believes it has a session and this node
        // does not. So this node opens its own handshake, and retries it like any other. Lost
        // packets alone must not leave the two sides stuck.
        val fixture = FallbackServiceFixture()
        fixture.announce()
        val opening = fixture.remoteNoise.initiateHandshake(
            fixture.service.myPeerID,
            fixture.remoteCrypto.getNoisePrivateKey(),
            fixture.remoteCrypto.getNoisePublicKey()
        )
        fixture.deliver(MessageType.NOISE_HANDSHAKE, opening)
        assertEquals(96, fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID).payload.size)

        val now = Clock.System.now().toEpochMilliseconds()
        fixture.service.sweepStalledHandshakes(now + HandshakeSupervisor.HANDSHAKE_TIMEOUT_MS + 1_000L)
        assertEquals(32, fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID).payload.size)
        eventually("the first attempt to be recorded") { fixture.service.handshakeSupervisorSize() == 1 }

        // That opening goes unanswered too; the next sweep past the backoff sends another.
        fixture.service.sweepStalledHandshakes(now + 60_000L)
        assertEquals(32, fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID).payload.size)
    }

    @Test
    fun aStalledHandshakeFromAPeerThatNeverAnnouncedIsOnlyDiscarded() = runTest {
        val fixture = FallbackServiceFixture()
        val opening = fixture.remoteNoise.initiateHandshake(
            fixture.service.myPeerID,
            fixture.remoteCrypto.getNoisePrivateKey(),
            fixture.remoteCrypto.getNoisePublicKey()
        )
        fixture.deliver(MessageType.NOISE_HANDSHAKE, opening)
        fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)

        fixture.service.sweepStalledHandshakes(
            Clock.System.now().toEpochMilliseconds() + HandshakeSupervisor.HANDSHAKE_TIMEOUT_MS + 1_000L
        )

        assertFalse(fixture.service.isHandshakeInFlight(fixture.remoteID))
        // A handshake of our own would be started on a launched coroutine, so give it time to show.
        fixture.connection.assertNoHandshakeFrom(fixture.service.myPeerID)
    }

    @Test
    fun aRecoveryThatNoLinkCanCarryIsOwedAndSentWhenTheLinkReturns() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()
        fixture.connection.deliverPackets = false
        val attemptsBefore = fixture.connection.broadcastAttempts
        repeat(3) { fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 3 }) }
        eventually("the recovery to be attempted") { fixture.connection.broadcastAttempts > attemptsBefore }
        eventually("the recovery to be put aside") { !fixture.service.isHandshakeInFlight(fixture.remoteID) }

        // Owed, but not as something the user asked for.
        eventually("the recovery to be owed") { fixture.service.automaticHandshakesOwedCount() == 1 }
        assertEquals(0, fixture.service.handshakesOwedCount())

        // The peer turns up on a link that can carry it: the handshake it is owed goes out.
        fixture.connection.deliverPackets = true
        fixture.announce(address = "another-address")
        assertEquals(32, fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID).payload.size)
        assertEquals(0, fixture.service.handshakesOwedCount())
    }

    @Test
    fun aHandshakeTheUserAskedForIsOwedAsTheUsers() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.connection.deliverPackets = false

        fixture.service.initiateNoiseHandshake(fixture.remoteID)

        eventually("the request to be remembered") { fixture.service.handshakesOwedCount() == 1 }
        assertEquals(0, fixture.service.automaticHandshakesOwedCount())
    }

    @Test
    fun aUserRequestTakesOverAHandshakeThisNodeStartedByItself() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()
        repeat(3) { fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 7 }) }
        fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        eventually("the recovery to be owed") { fixture.service.automaticHandshakesOwedCount() == 1 }
        assertEquals(0, fixture.service.handshakesOwedCount())

        // The recovery is still in flight when the user asks for a session with the same peer. No
        // second opening goes out, but the handshake is the user's now.
        assertTrue(fixture.service.isHandshakeInFlight(fixture.remoteID))
        fixture.service.initiateNoiseHandshake(fixture.remoteID)

        eventually("the handshake to become the user's") {
            fixture.service.handshakesOwedCount() == 1 && fixture.service.automaticHandshakesOwedCount() == 0
        }
    }

    @Test
    fun aLinkComingUpRestartsARecoveryHandshakeWithoutMakingItTheUsers() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()
        repeat(3) { fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 5 }) }
        fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        eventually("the recovery to be owed") { fixture.service.automaticHandshakesOwedCount() == 1 }

        // A packet under the peer's id on another address looks like the peer moving links. Past
        // the restart grace that restarts our handshake; it must not also record it as one the
        // user asked for, because nothing about that packet is authenticated.
        withContext(Dispatchers.Default) { delay(HandshakeRefreshPolicy.HANDSHAKE_RESTART_GRACE_MS + 300) }
        fixture.announce(address = "another-address")
        fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)

        assertEquals(0, fixture.service.handshakesOwedCount())
        assertEquals(1, fixture.service.automaticHandshakesOwedCount())
    }

    @Test
    fun onlyWhatTheUserDoesMarksAPeerAsChosen() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()
        // A session the peer opened, and packets from it, say nothing about the user.
        fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 6 })
        assertFalse(fixture.service.sessionIsChosenByUser(fixture.remoteID))

        fixture.service.sendPrivateMessage("hello", fixture.remoteID, "remote")
        assertTrue(fixture.service.sessionIsChosenByUser(fixture.remoteID))

        val asked = FallbackServiceFixture()
        asked.service.initiateNoiseHandshake(asked.remoteID)
        assertTrue(asked.service.sessionIsChosenByUser(asked.remoteID))
    }

    @Test
    fun aPrivateMessageThatCannotBeEncodedIsRefusedBeforeAnythingIsStartedForIt() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()

        // One byte more than the one-byte length of the private message encoding can say. The
        // answer comes on the caller's thread: no coroutine exists that could still send for it.
        assertFalse(fixture.service.sendPrivateMessage("x".repeat(256), fixture.remoteID, "remote", "too-long"))

        // So the longest that fits is the first thing this session encrypts, and reads back whole.
        val longest = "x".repeat(255)
        assertTrue(fixture.service.sendPrivateMessage(longest, fixture.remoteID, "remote", "longest"))
        val encrypted = fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID)
        assertContentEquals(ByteArray(4), encrypted.payload.copyOfRange(0, 4), "the session's first nonce")
        assertContentEquals(
            NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, PrivateMessagePacket("longest", longest).encode()!!).encode(),
            assertNotNull(fixture.remoteNoise.decrypt(fixture.service.myPeerID, encrypted.payload)).plaintext
        )
    }

    @Test
    fun withoutARadioAMessageGoesToTheBluetoothLinksWhateverTheMeshKnowsOfItsPeer() = runTest {
        // As it always did: this fixture's remote peer never announced itself, so the mesh does not
        // have it, and there is no radio that could be the reason the message was handed over.
        val fixture = FallbackServiceFixture()
        fixture.establish()
        assertNull(fixture.service.getPeerInfo(fixture.remoteID))
        assertTrue(fixture.service.sendPrivateMessage("hello", fixture.remoteID, "remote", "id-1"))

        assertNotNull(fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID))
        assertTrue(fixture.delegate.failures.isEmpty())
    }

    @Test
    fun privateMessagesLeaveInTheOrderTheyWereHandedOverEachWithALaterTime() = runTest {
        val fixture = FallbackServiceFixture()
        fixture.establish()
        // A long text goes out as several messages, handed over one right after the other.
        val texts = List(60) { "piece $it" }

        texts.forEachIndexed { index, text ->
            assertTrue(fixture.service.sendPrivateMessage(text, fixture.remoteID, "remote", "id-$index"))
        }
        val packets = List(texts.size) { fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID) }

        // In the order they reached the links: encrypted one after another, each dated later than
        // the one before (the upstream clients sort by that time), each the message it should be.
        assertEquals(List(texts.size) { it.toLong() }, packets.map { nonceOf(it) })
        assertTrue(packets.zipWithNext().all { (earlier, later) -> earlier.timestamp < later.timestamp })
        assertEquals(
            texts.mapIndexed { index, text -> "id-$index" to text },
            packets.map { packet ->
                val plaintext = assertNotNull(fixture.remoteNoise.decrypt(fixture.service.myPeerID, packet.payload)).plaintext
                val message = assertNotNull(PrivateMessagePacket.decode(assertNotNull(NoisePayload.decode(plaintext)).data))
                message.messageID to message.content
            }
        )
    }

    @Test
    fun aWriteThatHangsDoesNotHoldUpTheMessagesBehindItAndIsNotCancelled() = runTest {
        val fixture = FallbackServiceFixture(privateSendHandoverWaitMs = 300)
        fixture.establish()
        val release = CompletableDeferred<Unit>()
        fixture.connection.holdNextBroadcast = release

        fixture.service.sendPrivateMessage("stuck", fixture.remoteID, "remote", "first")
        fixture.service.sendPrivateMessage("behind it", fixture.remoteID, "remote", "second")

        // The second is not waited for longer than the handover wait; the first is still held.
        val second = fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID)
        assertEquals(1L, nonceOf(second))

        // And the held write was left alone: once the link lets go, it goes out.
        release.complete(Unit)
        assertEquals(0L, nonceOf(fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID)))
    }

    @Test
    fun aLinkThatHangsCostsMessagesSentTogetherOneWaitBetweenThem() = runTest {
        val fixture = FallbackServiceFixture(privateSendHandoverWaitMs = 1_000)
        fixture.establish()
        // A link that answers no write at all: every packet handed to it hangs, not just one.
        fixture.connection.holdEveryBroadcast = CompletableDeferred()
        val before = fixture.connection.broadcastAttempts

        val started = TimeSource.Monotonic.markNow()
        repeat(5) { index -> fixture.service.sendPrivateMessage("piece $index", fixture.remoteID, "remote", "id-$index") }
        eventually("all five to be handed to the links") { fixture.connection.broadcastAttempts == before + 5 }

        // One wait of a second, which they share. A wait for each would be four seconds.
        assertTrue(started.elapsedNow() < 3_500.milliseconds, "took ${started.elapsedNow()}")
    }

    @Test
    fun whileAWriteHangsNothingWaitsBehindItAgain() = runTest {
        val fixture = FallbackServiceFixture(privateSendHandoverWaitMs = 2_000)
        fixture.establish()
        fixture.connection.holdEveryBroadcast = CompletableDeferred()
        val before = fixture.connection.broadcastAttempts
        fixture.service.sendPrivateMessage("stuck", fixture.remoteID, "remote", "first")
        fixture.service.sendPrivateMessage("waits once", fixture.remoteID, "remote", "second")
        eventually("the second to stop waiting for the first") { fixture.connection.broadcastAttempts == before + 2 }

        // The write that was waited for in vain still hangs: a message sent now does not wait.
        val started = TimeSource.Monotonic.markNow()
        fixture.service.sendPrivateMessage("does not wait", fixture.remoteID, "remote", "third")
        eventually("the third to be handed to the links") { fixture.connection.broadcastAttempts == before + 3 }

        assertTrue(started.elapsedNow() < 1_000.milliseconds, "took ${started.elapsedNow()}")
    }

    @Test
    fun aMessageIsNotHeldUpForAHandoverToSomebodyElse() = runTest {
        // A wait nobody could sit out: were the second peer's message held for the first peer's
        // write, or behind the message that does wait for it, it would not arrive in this test's
        // lifetime.
        val fixture = FallbackServiceFixture(privateSendHandoverWaitMs = 600_000)
        fixture.establish()
        val other = FallbackRemote("3")
        fixture.establish(other)
        val release = CompletableDeferred<Unit>()
        fixture.connection.holdOnlyFor = fixture.remoteID
        fixture.connection.holdNextBroadcast = release

        fixture.service.sendPrivateMessage("stuck", fixture.remoteID, "remote", "first")
        fixture.service.sendPrivateMessage("waits behind it", fixture.remoteID, "remote", "second")
        fixture.service.sendPrivateMessage("for someone else", other.id, "other", "third")

        val arrived = fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID)
        assertContentEquals(other.id.hexToBytes(), arrived.recipientID)

        release.complete(Unit)
        val toFirstPeer = List(2) { fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID) }
        assertTrue(toFirstPeer.all { it.recipientID.contentEquals(fixture.remoteID.hexToBytes()) })
        assertEquals(listOf(0L, 1L), toFirstPeer.map { nonceOf(it) })
    }

    @Test
    fun messagesToOnePeerKeepTheirOrderWhenOneForAnotherPeerComesBetweenThem() = runTest {
        // A wait nobody could sit out, so only the order the service keeps decides what follows.
        val fixture = FallbackServiceFixture(privateSendHandoverWaitMs = 600_000)
        fixture.establish()
        val other = FallbackRemote("3")
        fixture.establish(other)
        // The first message to the first peer is slow to reach its link.
        val release = CompletableDeferred<Unit>()
        fixture.connection.holdOnlyFor = fixture.remoteID
        fixture.connection.holdOnlyNonce = 0
        fixture.connection.holdNextBroadcast = release

        fixture.service.sendPrivateMessage("first", fixture.remoteID, "remote", "a1")
        fixture.service.sendPrivateMessage("for someone else", other.id, "other", "b1")
        fixture.service.sendPrivateMessage("second", fixture.remoteID, "remote", "a2")

        // The other peer's message is held up by neither.
        assertContentEquals(other.id.hexToBytes(), fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID).recipientID)
        // Time for the second message to overtake the first, were it not waiting behind it.
        withContext(Dispatchers.Default) { delay(300) }
        release.complete(Unit)

        val toFirstPeer = List(2) { fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID) }
        assertTrue(toFirstPeer.all { it.recipientID.contentEquals(fixture.remoteID.hexToBytes()) })
        assertEquals(listOf(0L, 1L), toFirstPeer.map { nonceOf(it) })
    }

    @Test
    fun aPrivateFileNeverCarriesTheTimeOfAPrivateText() = runTest {
        // Sender, time and type are how a receiver tells a repeated packet from a new one, and a
        // text and a file are the same type on the air. Texts sent in a burst are dated ahead of
        // the clock, one millisecond apart: a file dated by the clock alone would land on one.
        val fixture = FallbackServiceFixture()
        fixture.establish()
        val file = BitchatFilePacket(fileName = "note.txt", fileSize = 3, mimeType = "text/plain", content = byteArrayOf(1, 2, 3))

        repeat(40) { round ->
            repeat(5) { index -> fixture.service.sendPrivateMessage("piece $round/$index", fixture.remoteID, "remote", "id-$round-$index") }
            assertTrue(fixture.service.sendFilePrivate(fixture.remoteID, file))
        }
        val times = List(40 * 6) { fixture.connection.awaitEncryptedFrom(fixture.service.myPeerID).timestamp }

        assertEquals(times.size, times.distinct().size)
    }

    @Test
    fun aRecordStaysWhileItsHandshakeIsOwedAndGoesOnceItIsNot() = runTest {
        // Room for ONE owed handshake of each kind, so a second recovery pushes the first one out.
        val fixture = FallbackServiceFixture(maxOwedHandshakes = 1)
        fixture.establish()
        repeat(3) { fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 4 }) }
        fixture.connection.awaitHandshakeFrom(fixture.service.myPeerID)
        eventually("the first attempt to be recorded") { fixture.service.handshakeSupervisorSize() == 1 }
        val now = Clock.System.now().toEpochMilliseconds()
        val old = now + HandshakeSupervisor.HANDSHAKE_RECORD_MAX_AGE_MS + 60_000L

        // Old, but still owed: the record stays.
        fixture.service.sweepStalledHandshakes(old)
        assertEquals(1, fixture.service.handshakeSupervisorSize())
        assertEquals(1, fixture.service.handshakeStartedAtCount())

        // An unreadable packet under another id asks for a recovery of its own, which takes the
        // one owed place. The first peer is no longer owed and no longer in flight.
        fixture.deliver(MessageType.NOISE_ENCRYPTED, ByteArray(48) { 9 }, sender = "00112233445566ff")
        eventually("the second attempt to be recorded") { fixture.service.handshakeSupervisorSize() == 2 }
        assertEquals(1, fixture.service.automaticHandshakesOwedCount())

        // The second record is as new as the clock; only the first is old enough to go.
        fixture.service.sweepStalledHandshakes(old)
        assertEquals(1, fixture.service.handshakeSupervisorSize())
        assertEquals(1, fixture.service.handshakeStartedAtCount())
    }

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

/** A clock that only goes forward, and only when a test says so. */
private class SteppedTimeSource : TimeSource {
    private val now = AtomicLong()
    fun advance(by: Duration) {
        now.addAndGet(by.inWholeMilliseconds)
    }
    override fun markNow(): TimeMark = object : TimeMark {
        private val start = now.get()
        override fun elapsedNow(): Duration = (now.get() - start).milliseconds
    }
}

private class SettableClock : Clock {
    @Volatile private var millis = Clock.System.now().toEpochMilliseconds()
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    fun advanceBy(deltaMillis: Long) { millis += deltaMillis }
}

/** Another peer than the fixture's own remote one, with a session of its own to establish. */
private class FallbackRemote(seedDigit: String) {
    val crypto = CryptoSigningFacade(seedDigit.repeat(64))
    val id: String = crypto.getIdentityFingerprint()
    val noise = NoiseEncryptionFacade(id)
}

private class FallbackServiceFixture(
    maxOwedHandshakes: Int = HandshakeSupervisor.MAX_OWED_HANDSHAKES,
    privateSendHandoverWaitMs: Long = BluetoothMeshService.PRIVATE_SEND_HANDOVER_WAIT_MS,
) {
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
            failureClock = failureClock,
            maxOwedHandshakes = maxOwedHandshakes,
            privateSendHandoverWaitMs = privateSendHandoverWaitMs
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

    suspend fun establish(other: FallbackRemote) {
        val address = "address-of-${other.id}"
        val message1 = other.noise.initiateHandshake(service.myPeerID, other.crypto.getNoisePrivateKey(), other.crypto.getNoisePublicKey())
        deliver(MessageType.NOISE_HANDSHAKE, message1, address = address, sender = other.id)
        val message2 = connection.awaitHandshakeFrom(service.myPeerID)
        val message3 = response(other.noise.processHandshake(
            service.myPeerID, message2.payload, other.crypto.getNoisePrivateKey(), other.crypto.getNoisePublicKey()
        ))
        deliver(MessageType.NOISE_HANDSHAKE, message3, address = address, sender = other.id)
        eventually("session with the other peer") { service.hasEstablishedSession(other.id) }
    }

    suspend fun announce(address: String = "remote-address") {
        val payload = requireNotNull(IdentityAnnouncement("remote", remoteCrypto.getNoisePublicKey(), remoteCrypto.getSigningPublicKey()).encode())
        deliver(MessageType.ANNOUNCE, payload, SpecialRecipients.BROADCAST, address)
        eventually("active remote peer") { service.getPeerInfo(remoteID) != null }
    }

    fun deliver(
        type: MessageType,
        payload: ByteArray,
        recipient: ByteArray = service.myPeerID.hexToBytes(),
        address: String = "remote-address",
        sender: String = remoteID
    ) {
        val packet = BitchatPacket(type = type.value, senderID = sender.hexToBytes(), recipientID = recipient,
            timestamp = timestamp++, payload = payload, ttl = 1u)
        service.onPacketReceived(requireNotNull(BinaryProtocol.encode(packet)), address)
    }
}

private class FallbackRecordingConnectionService : BluetoothConnectionService {
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    var deliverPackets = true
    // Counted by handovers that run side by side.
    private val attempts = AtomicInteger()
    val broadcastAttempts: Int get() = attempts.get()
    override suspend fun connectToDevice(deviceAddress: String) = Unit
    override suspend fun confirmDevice() = Unit
    override suspend fun isDeviceConnecting(deviceAddress: String) = false
    override suspend fun disconnectDeviceByAddress(deviceAddress: String) = Unit
    override suspend fun clearConnections() = Unit
    /**
     * When set, the next encrypted packet (for [holdOnlyFor] and sent under [holdOnlyNonce], when
     * those are set too) waits for it before it is written: a link that does not answer.
     */
    @Volatile var holdNextBroadcast: CompletableDeferred<Unit>? = null
    @Volatile var holdOnlyFor: String? = null
    @Volatile var holdOnlyNonce: Long? = null

    /** While set, every encrypted packet waits for it: a link that answers no write at all. */
    @Volatile var holdEveryBroadcast: CompletableDeferred<Unit>? = null
    override suspend fun broadcastPacket(packetData: ByteArray): Boolean {
        attempts.incrementAndGet()
        if (!deliverPackets) return false
        val packet = BinaryProtocol.decode(packetData)
        val recipient = holdOnlyFor
        val nonce = holdOnlyNonce
        if (packet?.type == MessageType.NOISE_ENCRYPTED.value &&
            (recipient == null || packet.recipientID.contentEquals(recipient.hexToBytes())) &&
            (nonce == null || nonceOf(packet) == nonce)
        ) {
            holdNextBroadcast?.let { hold ->
                holdNextBroadcast = null
                hold.await()
            }
            holdEveryBroadcast?.await()
        }
        outgoing.send(packetData)
        return true
    }
    override fun hasRequiredPermissions() = true
    override fun setConnectionEstablishedCallback(callback: ConnectionEstablishedCallback) = Unit
    override fun setConnectionReadyCallback(callback: ConnectionReadyCallback) = Unit
    override fun setOnPacketReceivedCallback(callback: OnPacketReceivedCallback) = Unit

    suspend fun awaitHandshakeFrom(peerID: String): BitchatPacket = awaitPacket(peerID, MessageType.NOISE_HANDSHAKE)
    suspend fun awaitEncryptedFrom(peerID: String): BitchatPacket = awaitPacket(peerID, MessageType.NOISE_ENCRYPTED)
    suspend fun assertNoHandshakeFrom(peerID: String) {
        val packet = withContext(Dispatchers.Default) {
            withTimeoutOrNull(400.milliseconds) {
                while (true) {
                    val next = BinaryProtocol.decode(outgoing.receive()) ?: continue
                    if (next.type == MessageType.NOISE_HANDSHAKE.value && next.senderID.contentEquals(peerID.hexToBytes())) {
                        return@withTimeoutOrNull next
                    }
                }
                error("unreachable")
            }
        }
        assertNull(packet, "unexpected handshake packet")
    }

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
    // Read by a test while the service adds to it from its own threads.
    val messages = CopyOnWriteArrayList<Pair<String, String>>()
    /** Message id and reason of every private message reported as not sent. */
    val failures = CopyOnWriteArrayList<Pair<String, String>>()
    override fun didFailToSendPrivateMessage(messageID: String, recipientPeerID: String, reason: String) {
        failures += messageID to reason
    }
    override fun didReceiveMessage(message: com.bitchat.domain.chat.model.BitchatMessage) = Unit
    override fun didReceiveAuthenticatedPrivateMessage(message: com.bitchat.domain.chat.model.BitchatMessage) {
        messages += message.id to message.content
    }
    override fun didUpdatePeerList(peers: List<String>) = Unit
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) = Unit
    /** Message id and peer of every private message a peer said it received. */
    val delivered = CopyOnWriteArrayList<Pair<String, String>>()
    override fun didReceiveAuthenticatedDeliveryAck(messageID: String, recipientPeerID: String) {
        delivered += messageID to recipientPeerID
    }
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

/** Fails if [condition] becomes true at any moment of the next [forMillis] milliseconds. */
/** A time by which every deadline that began before now has passed. */
private fun muchLater(): Long = Clock.System.now().toEpochMilliseconds() + 60 * 60 * 1000L

private suspend fun never(description: String, forMillis: Long, condition: suspend () -> Boolean) {
    val happened = withContext(Dispatchers.Default) {
        withTimeoutOrNull(forMillis) {
            while (!condition()) delay(10)
            true
        } ?: false
    }
    assertFalse(happened, "did not expect $description")
}

private suspend fun eventually(description: String, condition: suspend () -> Boolean) {
    // The service works on its own dispatcher in real time; runTest's clock is virtual. The wait
    // is long because this has timed out at five seconds on a machine busy with another build
    // (load average above 80); it costs nothing when the condition is reached.
    val reached = withContext(Dispatchers.Default) {
        withTimeoutOrNull(30.seconds) {
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

private fun exactPacket(
    sender: String,
    recipient: String,
    type: MessageType = MessageType.NOISE_HANDSHAKE,
    payload: ByteArray = byteArrayOf(1, 2, 3),
    ttl: UByte = 0u,
): ByteArray {
    val packet = BitchatPacket(
        type = type.value,
        senderID = sender.hexToBytes(),
        recipientID = recipient.hexToBytes(),
        timestamp = 1u,
        payload = payload,
        ttl = ttl,
    )
    return MessagePadding.unpad(requireNotNull(BinaryProtocol.encode(packet))).also { data ->
        if (payload.size == 203) {
            check(data.size == MAX_LORA_PACKET_BYTES + 1)
        } else {
            check(data.size <= MAX_LORA_PACKET_BYTES)
        }
    }
}

private suspend fun BluetoothMeshService.assertNoLoRaIngressState(peerID: String) {
    assertFalse(hasNoiseCandidate(peerID))
    assertFalse(hasValidatedNoiseSession(peerID))
    assertEquals(0, pendingEncryptedPayloadCount())
    assertEquals(0, failureCount(peerID))
    assertEquals(0, handshakesOwedCount())
    assertEquals(0, automaticHandshakesOwedCount())
    assertEquals(0, handshakeStartedAtCount())
    assertEquals(0, handshakeSupervisorSize())
    assertFalse(getDeviceAddressToPeerMapping().containsValue(peerID))
}

/** The Noise nonce an encrypted packet was sent under: the four bytes before its ciphertext. */
private fun nonceOf(packet: BitchatPacket): Long =
    packet.payload.take(4).fold(0L) { nonce, byte -> (nonce shl 8) or (byte.toLong() and 0xFF) }
