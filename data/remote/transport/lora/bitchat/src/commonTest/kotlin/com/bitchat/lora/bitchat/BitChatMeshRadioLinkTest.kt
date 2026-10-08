package com.bitchat.lora.bitchat

import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.bitchat.protocol.MeshPacketFrame
import com.bitchat.lora.bitchat.transmit.Cause
import com.bitchat.lora.bitchat.transmit.Ledger
import com.bitchat.lora.bitchat.transmit.LoRaTransmitter
import com.bitchat.lora.bitchat.transmit.TransmitKind
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import com.bitchat.lora.radio.airtimeMicros
import com.bitchat.transport.RadioPurpose
import com.bitchat.transport.RadioSendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Whose time on air pays for a mesh packet handed to the radio. The frames of a Noise handshake are
 * 67, 131 and 99 bytes (5 of frame and 30 of packet around messages of 32, 96 and 64 bytes): at SF9
 * and 125 kHz 411, 697 and 554 ms on the air.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BitChatMeshRadioLinkTest {
    private val config = LoRaConfig()
    private val opening = ByteArray(62) { 1 }
    private val answer = ByteArray(126) { 2 }
    private val final = ByteArray(94) { 3 }
    private val alice = "a1b2c3d4e5f60708"
    private val bob = "1112131415161718"

    private fun onAir(frameBytes: Int) = config.airtimeMicros(frameBytes)

    private class Radio : BitChatRadio {
        override val events = MutableSharedFlow<LoRaEvent>()
        override var isReady = true
        val frames = mutableListOf<ByteArray>()
        override fun configure(config: LoRaConfig) = true
        override fun startReceiving() = Unit
        override fun send(data: ByteArray): Boolean { frames += data; return true }
        override suspend fun shutdown() = Unit
    }

    private inner class Fixture(scope: TestScope) {
        val radio = Radio()
        val transmitter = LoRaTransmitter(radio, { scope.testScheduler.currentTime }) { it.first }
        var heard = listOf<LoRaPeer>()
        var radioConfig: LoRaConfig? = config
        val link = BitChatMeshRadioLink(transmitter, { heard }, { radioConfig })

        suspend fun local() = transmitter.takenMicros(Ledger.LOCAL_ORIGIN)
        suspend fun remote() = transmitter.takenMicros(Ledger.REMOTE_SOLICITED)
    }

    private suspend fun TestScope.fixture() = Fixture(this).also { it.transmitter.start(config, backgroundScope) }

    @Test
    fun aDeviceIsHeardByItsIdWhateverTheCase() = runTest {
        val fixture = fixture()
        fixture.heard = listOf(LoRaPeer(alice, "alice", Instant.fromEpochSeconds(0), -80, 6f))
        assertTrue(fixture.link.hears(alice))
        assertTrue(fixture.link.hears(alice.uppercase()))
        assertFalse(fixture.link.hears(bob))
    }

    @Test
    fun theUsersOpeningGoesOutAsAPacketFrameAndSetsAsideTheTimeOfWhateverFollowsIt() = runTest {
        val fixture = fixture()
        assertEquals(RadioSendResult.SENT, fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true)))

        assertContentEquals(opening, MeshPacketFrame.decode(fixture.radio.frames.single()))
        // Its own time is spent, and the longer of the two messages that can follow is held back.
        assertEquals(onAir(67) + onAir(131), fixture.local())
        assertEquals(0L, fixture.remote())
        assertEquals(1, fixture.link.keptHolds())
    }

    @Test
    fun theLastMessageOfTheUsersHandshakeIsPaidFromItsHoldAndGivesTheRestBack() = runTest {
        val fixture = fixture()
        fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true))
        assertEquals(RadioSendResult.SENT, fixture.link.send(final, alice.uppercase(), RadioPurpose.HandshakeFinal))

        assertEquals(onAir(67) + onAir(99), fixture.local())
        assertEquals(0L, fixture.remote())
        assertEquals(0, fixture.link.keptHolds())
    }

    @Test
    fun theAnswerOfAUserWhoAlsoOpenedIsPaidFromThatHold() = runTest {
        val fixture = fixture()
        fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true))
        assertEquals(RadioSendResult.SENT, fixture.link.send(answer, alice, RadioPurpose.HandshakeAnswer))

        assertEquals(onAir(67) + onAir(131), fixture.local())
        assertEquals(0L, fixture.remote())
        assertEquals(0, fixture.link.keptHolds())
    }

    @Test
    fun whatTheUsersHoldCannotCarryIsPaidLikeAnyOtherAnswerAndTheHoldIsGone() = runTest {
        val fixture = fixture()
        fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true))
        // A frame of 135 bytes is 718 ms on the air: more than the 697 held, within the 750 for strangers.
        assertEquals(RadioSendResult.SENT, fixture.link.send(ByteArray(130), alice, RadioPurpose.HandshakeAnswer))

        assertEquals(onAir(135), fixture.transmitter.takenMicros(Cause.Unauthenticated))
        assertEquals(onAir(67), fixture.local())
        assertEquals(0, fixture.link.keptHolds())
    }

    @Test
    fun anAnswerToSomeoneElsesOpeningIsNeverPaidByTheUser() = runTest {
        val fixture = fixture()
        assertEquals(RadioSendResult.SENT, fixture.link.send(answer, alice, RadioPurpose.HandshakeAnswer))
        assertEquals(onAir(131), fixture.transmitter.takenMicros(Cause.Unauthenticated))
        assertEquals(0L, fixture.local())

        // One such answer a minute, whoever asks: the next is refused, and still nothing of the user's is touched.
        assertEquals(RadioSendResult.NO_TIME_ON_AIR, fixture.link.send(answer, bob, RadioPurpose.HandshakeAnswer))
        assertEquals(0L, fixture.local())
        assertEquals(1, fixture.radio.frames.size)
    }

    @Test
    fun theLastMessageOfAHandshakeThatWasNotTheUsersIsPaidAsThatValidatedPeers() = runTest {
        val fixture = fixture()
        assertEquals(RadioSendResult.SENT, fixture.link.send(final, alice.uppercase(), RadioPurpose.HandshakeFinal))
        assertEquals(onAir(99), fixture.transmitter.takenMicros(Cause.Validated(alice)))
        assertEquals(0L, fixture.transmitter.takenMicros(Cause.Unauthenticated))
        assertEquals(0L, fixture.local())

        assertEquals(RadioSendResult.NO_TIME_ON_AIR, fixture.link.send(final, alice, RadioPurpose.HandshakeFinal))
        assertEquals(RadioSendResult.SENT, fixture.link.send(final, bob, RadioPurpose.HandshakeFinal))
        assertEquals(0L, fixture.local())
    }

    @Test
    fun aHoldKeptForOnePeerPaysNothingAddressedToAnother() = runTest {
        val fixture = fixture()
        fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true))
        assertEquals(RadioSendResult.SENT, fixture.link.send(final, bob, RadioPurpose.HandshakeFinal))
        assertEquals(RadioSendResult.SENT, fixture.link.send(ByteArray(40), bob, RadioPurpose.HandshakeAnswer))

        assertEquals(onAir(67) + onAir(131), fixture.local())
        assertEquals(onAir(99), fixture.transmitter.takenMicros(Cause.Validated(bob)))
        assertEquals(onAir(45), fixture.transmitter.takenMicros(Cause.Unauthenticated))
        assertEquals(1, fixture.link.keptHolds())
    }

    @Test
    fun anOpeningThisDeviceStartsByItselfIsNeverPaidByTheUserAndNeitherIsWhatFollowsIt() = runTest {
        val fixture = fixture()
        assertEquals(RadioSendResult.SENT, fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = false)))
        assertEquals(onAir(67), fixture.transmitter.takenMicros(Cause.Unauthenticated))
        assertEquals(0, fixture.link.keptHolds())

        assertEquals(RadioSendResult.SENT, fixture.link.send(final, alice, RadioPurpose.HandshakeFinal))
        assertEquals(onAir(99), fixture.transmitter.takenMicros(Cause.Validated(alice)))
        assertEquals(0L, fixture.local())
    }

    @Test
    fun aSecondOpeningToTheSamePeerReplacesTheHoldOfTheFirst() = runTest {
        val fixture = fixture()
        fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true))
        fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true))

        assertEquals(2 * onAir(67) + onAir(131), fixture.local())
        assertEquals(1, fixture.link.keptHolds())
    }

    @Test
    fun withoutTimeForAllOfTheHandshakeNotEvenItsOpeningIsSent() = runTest {
        val fixture = fixture()
        // Three full frames leave 495 of the user's 4,000 ms: enough for the opening alone, not for
        // what follows it.
        fixture.transmitter.offer(List(3) { ByteArray(237) }, TransmitKind.PUBLIC_MESSAGE)!!.forEach { it.await() }
        val before = fixture.local()

        assertEquals(RadioSendResult.NO_TIME_ON_AIR, fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true)))
        assertEquals(3, fixture.radio.frames.size)
        assertEquals(before, fixture.local())
        assertEquals(0, fixture.link.keptHolds())
    }

    @Test
    fun aPacketThatDoesNotFitOneFrameOrAStoppedRadioSendsNothing() = runTest {
        val fixture = fixture()
        assertEquals(RadioSendResult.FAILED, fixture.link.send(ByteArray(233), alice, RadioPurpose.PrivateMessage))
        assertEquals(RadioSendResult.FAILED, fixture.link.send(ByteArray(0), alice, RadioPurpose.PrivateMessage))
        assertEquals(RadioSendResult.SENT, fixture.link.send(ByteArray(232), alice, RadioPurpose.PrivateMessage))
        assertEquals(1, fixture.radio.frames.size)

        fixture.radioConfig = null
        assertEquals(RadioSendResult.FAILED, fixture.link.send(opening, alice, RadioPurpose.PrivateMessage))
        assertEquals(1, fixture.radio.frames.size)
    }

    @Test
    fun aPrivateMessageIsPaidByTheUser() = runTest {
        val fixture = fixture()
        assertEquals(RadioSendResult.SENT, fixture.link.send(ByteArray(232), alice, RadioPurpose.PrivateMessage))
        assertEquals(onAir(237), fixture.local())
        assertEquals(0L, fixture.remote())
    }

    @Test
    fun aDeliveryAcknowledgementIsPaidByTheValidatedPeer() = runTest {
        val fixture = fixture()
        assertEquals(RadioSendResult.SENT, fixture.link.send(ByteArray(56), alice, RadioPurpose.DeliveryAck))
        assertEquals(onAir(61), fixture.transmitter.takenMicros(Cause.Validated(alice)))
        assertEquals(0L, fixture.local())
        assertEquals(0, fixture.link.keptHolds())
    }

    @Test
    fun anOpeningThatDidNotGoOutGivesItsHoldBack() = runTest {
        val fixture = fixture()
        fixture.radio.isReady = false
        assertEquals(RadioSendResult.FAILED, fixture.link.send(opening, alice, RadioPurpose.HandshakeOpening(byUser = true)))
        assertEquals(0L, fixture.local())
        assertEquals(0, fixture.link.keptHolds())
    }

    @Test
    fun holdsOfHandshakesThatWereNeverAnsweredDoNotPileUp() = runTest {
        val fixture = fixture()
        repeat(9) { peer ->
            assertEquals(
                RadioSendResult.SENT,
                fixture.link.send(opening, "000000000000000$peer", RadioPurpose.HandshakeOpening(byUser = true)),
            )
            // Long enough for the hold to run out and for the opening's time to be the user's again.
            advanceTimeBy(61_000)
        }
        assertEquals(8, fixture.link.keptHolds())
        assertNotNull(fixture.link.send(final, "0000000000000008", RadioPurpose.HandshakeFinal))
    }
}
