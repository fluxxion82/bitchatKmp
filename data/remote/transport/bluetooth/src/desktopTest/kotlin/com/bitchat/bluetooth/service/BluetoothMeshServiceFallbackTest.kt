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
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
            fixture.service.sendFilePrivate(fixture.remoteID, file)
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

/** Fails if [condition] becomes true at any moment of the next [forMillis] milliseconds. */
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
