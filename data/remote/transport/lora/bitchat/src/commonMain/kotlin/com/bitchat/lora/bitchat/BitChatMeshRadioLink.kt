package com.bitchat.lora.bitchat

import com.bitchat.lora.LoRaPeer
import com.bitchat.lora.bitchat.protocol.MeshPacketFrame
import com.bitchat.lora.bitchat.transmit.Cause
import com.bitchat.lora.bitchat.transmit.LoRaTransmitter
import com.bitchat.lora.bitchat.transmit.TransmitKind
import com.bitchat.lora.bitchat.transmit.TransmitQueue
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.airtimeMicros
import com.bitchat.transport.MeshRadioLink
import com.bitchat.transport.RadioPurpose
import com.bitchat.transport.RadioSendResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The radio as a way to one peer of the mesh: one whole packet per frame, and for each kind of packet
 * the ledger of time on air that pays for it.
 *
 * Nothing a remote party sends can make this device spend what belongs to its own user, except by
 * using up the hold that the user's own handshake with that very peer created: an answer or a last
 * message for a peer without such a hold is paid from what remote parties may spend.
 */
internal class BitChatMeshRadioLink(
    private val transmitter: LoRaTransmitter,
    private val heardPeers: () -> List<LoRaPeer>,
    private val config: () -> LoRaConfig?,
) : MeshRadioLink {
    private val mutex = Mutex()
    private val userHandshakes = LinkedHashMap<String, TransmitQueue.Hold>()
    private var nextFrameId = 0u

    override fun hears(peerID: String): Boolean =
        heardPeers().any { it.deviceId.equals(peerID, ignoreCase = true) }

    override suspend fun send(packet: ByteArray, peerID: String, purpose: RadioPurpose): RadioSendResult {
        val frame = mutex.withLock {
            MeshPacketFrame.encode(nextFrameId.toUShort(), packet)?.also { nextFrameId++ }
        } ?: return RadioSendResult.FAILED
        val radioConfig = config() ?: return RadioSendResult.FAILED
        val peer = peerID.lowercase()
        var opened: TransmitQueue.Hold? = null
        val ticket = when (purpose) {
            is RadioPurpose.HandshakeOpening ->
                if (purpose.byUser) {
                    openingForUser(frame, peer, radioConfig)?.let { (ticket, hold) -> opened = hold; ticket }
                } else {
                    transmitter.offer(listOf(frame), TransmitKind.RECOVERY_OPENING, Cause.Unauthenticated)?.single()
                }
            RadioPurpose.HandshakeAnswer ->
                following(frame, peer, TransmitKind.LOCAL_HANDSHAKE_ANSWER, TransmitKind.HANDSHAKE_ANSWER, Cause.Unauthenticated)
            RadioPurpose.HandshakeFinal ->
                following(frame, peer, TransmitKind.LOCAL_HANDSHAKE_FINAL, TransmitKind.HANDSHAKE_FINAL, Cause.Validated(peer))
            RadioPurpose.PrivateMessage -> transmitter.offer(listOf(frame), TransmitKind.PRIVATE_MESSAGE)?.single()
            RadioPurpose.DeliveryAck -> transmitter.offer(listOf(frame), TransmitKind.DELIVERY_ACK, Cause.Validated(peer))?.single()
        } ?: return RadioSendResult.NO_TIME_ON_AIR

        val outcome = ticket.await()
        // An opening that did not go out has nothing to follow it: its hold is given back, unless
        // another opening to this peer has replaced it meanwhile.
        opened?.takeIf { outcome != LoRaTransmitter.Outcome.SENT }?.let { hold ->
            mutex.withLock {
                if (userHandshakes[peer] === hold) userHandshakes.remove(peer)
                transmitter.release(hold)
            }
        }
        return if (outcome == LoRaTransmitter.Outcome.SENT) RadioSendResult.SENT else RadioSendResult.FAILED
    }

    /**
     * The opening of a handshake the user asked for, out of a hold that also covers whichever message
     * of that handshake this device sends next (its last one, or its answer when both sides opened).
     */
    private suspend fun openingForUser(
        frame: ByteArray,
        peerID: String,
        radioConfig: LoRaConfig,
    ): Pair<LoRaTransmitter.Ticket, TransmitQueue.Hold>? = mutex.withLock {
        userHandshakes.remove(peerID)?.let { transmitter.release(it) }
        val later = maxOf(radioConfig.airtimeMicros(FINAL_FRAME_BYTES), radioConfig.airtimeMicros(ANSWER_FRAME_BYTES))
        val hold = transmitter.hold(radioConfig.airtimeMicros(frame.size) + later, places = 2) ?: return@withLock null
        val ticket = transmitter.offer(listOf(frame), TransmitKind.LOCAL_HANDSHAKE_OPENING, hold = hold)?.single()
        if (ticket == null) {
            transmitter.release(hold)
            return@withLock null
        }
        userHandshakes[peerID] = hold
        while (userHandshakes.size > MAX_USER_HANDSHAKES) {
            userHandshakes.entries.iterator().next().also { oldest ->
                userHandshakes.remove(oldest.key)
                transmitter.release(oldest.value)
            }
        }
        ticket to hold
    }

    /**
     * A later message of a handshake: out of the hold of the user's own handshake with this peer when
     * there is one (which it uses up), and otherwise, or when that hold cannot carry it, from what
     * remote parties may spend.
     */
    private suspend fun following(
        frame: ByteArray,
        peerID: String,
        outOfHold: TransmitKind,
        otherwise: TransmitKind,
        cause: Cause,
    ): LoRaTransmitter.Ticket? = mutex.withLock {
        val hold = userHandshakes.remove(peerID)
        val held = hold?.let {
            transmitter.offer(listOf(frame), outOfHold, hold = it)?.single().also { _ -> transmitter.release(it) }
        }
        held ?: transmitter.offer(listOf(frame), otherwise, cause)?.single()
    }

    /** How many handshakes of the user have a hold kept for them, for tests. */
    internal suspend fun keptHolds(): Int = mutex.withLock { userHandshakes.size }

    /** A stopped radio has cancelled the reservations; do not retain stale handles into it. */
    suspend fun dropHolds() = mutex.withLock {
        userHandshakes.values.forEach { transmitter.release(it) }
        userHandshakes.clear()
    }

    private companion object {
        const val MAX_USER_HANDSHAKES = 8
        // The frames of Noise XX messages 2 and 3: 5 bytes of frame, 30 of packet, 96 and 64 of Noise.
        const val ANSWER_FRAME_BYTES = 131
        const val FINAL_FRAME_BYTES = 99
    }
}
