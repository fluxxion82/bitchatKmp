package com.bitchat.bluetooth.handler

import com.bitchat.domain.base.logPath
import com.bitchat.domain.base.logBody
import com.bitchat.api.dto.mapper.toBitchatFilePacket
import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.facade.NoiseEncryptionFacade
import com.bitchat.bluetooth.manager.PeerManager
import com.bitchat.bluetooth.manager.SecurityManager
import com.bitchat.bluetooth.manager.SessionFailureTracker
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.IdentityAnnouncement
import com.bitchat.bluetooth.protocol.MessageType
import com.bitchat.bluetooth.protocol.SpecialRecipients
import com.bitchat.bluetooth.protocol.logDebug
import com.bitchat.bluetooth.protocol.logError
import com.bitchat.bluetooth.protocol.logInfo
import kotlin.time.Clock
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket

class MessageHandler(
    private val myPeerID: String,
    private val securityManager: SecurityManager,
    private val peerManager: PeerManager,
    private val cryptoSigning: CryptoSigningFacade,
    // The failure tracker's cooldown is measured on this clock, so a test can let it pass.
    private val clock: Clock = Clock.System,
    internal val pendingEncryptedPayloads: PendingEncryptedPayloads = PendingEncryptedPayloads()
) {
    var delegate: MessageHandlerDelegate? = null

    internal val pendingEncryptedPayloadCount: Int get() = pendingEncryptedPayloads.count

    // A session can report itself established and be unusable; this is what notices.
    private val sessionFailures = SessionFailureTracker()

    suspend fun handlePacket(packet: BitchatPacket, peerID: String) {
        val messageType = MessageType.fromValue(packet.type) ?: return

        when (messageType) {
            MessageType.ANNOUNCE -> handleAnnounce(packet, peerID)
            MessageType.MESSAGE -> handleMessage(packet, peerID)
            MessageType.NOISE_HANDSHAKE -> handleNoiseHandshake(packet, peerID)
            MessageType.NOISE_ENCRYPTED -> handleNoiseEncrypted(packet, peerID)
            MessageType.LEAVE -> handleLeave(packet, peerID)
            MessageType.FRAGMENT -> handleFragment(packet, peerID)
            MessageType.FILE_TRANSFER -> handleFileTransfer(packet, peerID)
            else -> {
                // Unsupported message type
            }
        }
    }

    private fun handleAnnounce(packet: BitchatPacket, peerID: String) {
        println("🔍 ANNOUNCE: Processing announcement from $peerID")

        // Decode announcement (tries TLV first, fallback to plain text)
        val announcement = IdentityAnnouncement.decode(packet.payload)

        if (announcement == null) {
            logError("MessageHandler", "Failed to decode ANNOUNCE from $peerID")
            return
        }

        println("🔍 ANNOUNCE: Nickname: '${announcement.nickname}', Has NoiseKey: ${announcement.noisePublicKey != null}, Has SigningKey: ${announcement.signingPublicKey != null}")

        peerManager.addOrUpdatePeer(
            peerID = peerID,
            nickname = announcement.nickname,
            noisePublicKey = announcement.noisePublicKey,
            signingPublicKey = announcement.signingPublicKey,
            isConnected = true,
            isDirectConnection = true
        )

        println("✅ ANNOUNCE: Peer $peerID added/updated as '${announcement.nickname}'")
        delegate?.onPeerAnnounced(peerID, announcement.nickname)
    }

    private fun handleMessage(packet: BitchatPacket, peerID: String) {
        val isBroadcast = packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)
        if (!isBroadcast) {
            dropAddressedPlaintext(packet, peerID, MessageType.MESSAGE, "private delivery requires Noise")
            return
        }

        if (peerID == myPeerID) {
            logDebug("MessageHandler", "Ignoring self-message (local echo exists)")
            return
        }

        delegate?.onMessageReceived(peerID, packet.payload.decodeToString())
    }

    private suspend fun handleNoiseHandshake(packet: BitchatPacket, peerID: String) {
        val localPrivateKey = cryptoSigning.getNoisePrivateKey()
        val localPublicKey = cryptoSigning.getNoisePublicKey()

        when (val result = securityManager.handleNoiseHandshake(
            packet = packet,
            peerID = peerID,
            localPrivateKey = localPrivateKey,
            localPublicKey = localPublicKey
        )) {
            is NoiseEncryptionFacade.HandshakeResult.Response -> {
                println("🔐 NOISE_HANDSHAKE: Generated response packet (${result.message.size} bytes)")
                delegate?.onHandshakeResponse(peerID, result.message)
            }

            is NoiseEncryptionFacade.HandshakeResult.Established -> {
                result.response?.let { responsePacket ->
                    println("🔐 NOISE_HANDSHAKE: Generated response packet (${responsePacket.size} bytes)")
                    delegate?.onHandshakeResponse(peerID, responsePacket)
                }
                println("✅ NOISE_HANDSHAKE: Session established with $peerID")
                delegate?.onSessionEstablished(peerID)
                processPendingEncryptedMessages(peerID)
            }

            NoiseEncryptionFacade.HandshakeResult.Ignored -> {
                println("⏳ NOISE_HANDSHAKE: Session not yet established with $peerID")
            }

            // The facade already logged the sole identity-rejection line, including only the
            // permitted 8-byte derived identifiers. In particular, don't treat an old live
            // session as a new establishment and flush its pending outbox here.
            NoiseEncryptionFacade.HandshakeResult.RejectedIdentity -> Unit
        }

        delegate?.onHandshakeReceived(peerID)
    }

    private fun handleNoiseEncrypted(packet: BitchatPacket, peerID: String) {
        if (peerID == myPeerID) {
            logDebug("MessageHandler", "Ignoring self-encrypted message")
            return
        }

        // Only decrypt what is addressed to us. Handshakes have always been filtered this way
        // (SecurityManager), but encrypted payloads were not, so a direct message this node was
        // merely relaying from A to C was fed into our own session with A. It cannot authenticate,
        // and the failed attempt leaves the session's receive nonce set to a counter from a
        // conversation we are not part of -- after which every real message from A is refused as
        // going backwards. In any mesh of three that silently and permanently kills direct
        // messages, and a forged packet does the same on purpose.
        if (!securityManager.isAddressedToUs(packet)) {
            // Logged because a dropped relay and a lost message are otherwise indistinguishable in
            // the journal, which already cost one debugging session.
            logDebug("MessageHandler", "Not ours to decrypt: encrypted payload from $peerID " +
                "addressed to ${packet.recipientID?.toHexString() ?: "nobody"}")
            return
        }

        // validatePacket has already recorded this packet as seen. If the payload is one a
        // handshake in flight could still make readable but there is no room to keep it, that
        // record would make every later copy (the mesh relays, so copies do arrive) look like a
        // duplicate, and the message would be lost for good.
        val result = handleEncryptedPayload(peerID, packet.payload, requeueOnFailure = true)
        if (result == EncryptedPayloadResult.REFUSED) securityManager.forgetPacket(packet, peerID)
    }

    private fun handleLeave(packet: BitchatPacket, peerID: String) {
        peerManager.disconnectPeer(peerID)
        delegate?.onPeerLeft(peerID)
    }

    private fun handleFragment(packet: BitchatPacket, peerID: String) {
        val isBroadcast = packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)
        if (!isBroadcast) {
            dropAddressedPlaintext(packet, peerID, MessageType.FRAGMENT, "private fragments are unsupported")
            return
        }

        delegate?.onFragmentReceived(peerID)
    }

    private fun handleFileTransfer(packet: BitchatPacket, peerID: String) {
        val isBroadcast = packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)
        if (!isBroadcast) {
            dropAddressedPlaintext(packet, peerID, MessageType.FILE_TRANSFER, "private delivery requires Noise")
            return
        }

        if (peerID == myPeerID) {
            logDebug("MessageHandler", "Ignoring self-file transfer (local echo exists)")
            return
        }

        logInfo("MessageHandler", "📎 Received file transfer from $peerID (${packet.payload.size} bytes)")

        val filePacket = packet.payload.toBitchatFilePacket()
        if (filePacket == null) {
            logError("MessageHandler", "Failed to decode file packet from $peerID")
            return
        }

        logInfo("MessageHandler", "📎 File: ${logPath(filePacket.fileName)} (${filePacket.fileSize} bytes, ${filePacket.mimeType})")

        delegate?.onPublicFileReceived(peerID, filePacket)
    }

    private fun handleEncryptedPayload(
        peerID: String,
        payload: ByteArray,
        requeueOnFailure: Boolean
    ): EncryptedPayloadResult {
        val decrypted = securityManager.decryptFromPeer(peerID, payload)
        if (decrypted != null) {
            val noisePayload = NoisePayload.decode(decrypted.plaintext)
            if (noisePayload == null) {
                println("❌ Failed to parse NoisePayload from $peerID")
                recordDecryptOutcome(peerID, decrypted.via)
                return EncryptedPayloadResult.READ
            }

            when (noisePayload.type) {
                NoisePayloadType.PRIVATE_MESSAGE -> {
                    val privateMessage = PrivateMessagePacket.decode(noisePayload.data)
                    if (privateMessage != null) {
                        println("✅ Decrypted message from $peerID: ${logBody(privateMessage.content, 50)}")
                        // A successful decrypt authenticated either the established session or
                        // its read-only validated predecessor. A2 validates identity at promotion.
                        delegate?.onAuthenticatedPrivateMessage(
                            peerID,
                            privateMessage.messageID,
                            privateMessage.content
                        )
                    } else {
                        println("❌ Failed to parse PrivateMessagePacket from $peerID")
                    }
                }

                NoisePayloadType.READ_RECEIPT -> {
                    delegate?.onAuthenticatedRead(peerID, noisePayload.data.decodeToString())
                }

                NoisePayloadType.DELIVERED -> {
                    delegate?.onAuthenticatedDelivered(peerID, noisePayload.data.decodeToString())
                }

                NoisePayloadType.FILE_TRANSFER -> {
                    val filePacket = noisePayload.data.toBitchatFilePacket()
                    if (filePacket != null) {
                        logInfo("MessageHandler", "📎 Received encrypted file from $peerID: ${logPath(filePacket.fileName)} (${filePacket.fileSize} bytes)")
                        delegate?.onAuthenticatedPrivateFile(peerID, filePacket)
                    } else {
                        logError("MessageHandler", "❌ Failed to decode encrypted file from $peerID")
                    }
                }
            }
            recordDecryptOutcome(peerID, decrypted.via)
            return EncryptedPayloadResult.READ
        } else if (requeueOnFailure) {
            val hasCandidate = securityManager.hasCandidate(peerID)
            val outcome = if (hasCandidate && queueEncryptedMessage(peerID, payload)) {
                EncryptedPayloadResult.KEPT
            } else if (hasCandidate) {
                EncryptedPayloadResult.REFUSED
            } else {
                EncryptedPayloadResult.UNREADABLE
            }
            condemnSessionIfHopeless(peerID, SessionFailureTracker.FailureEvidence.UNDECRYPTABLE)
            return outcome
        } else {
            println("❌ Failed to decrypt from $peerID, not requeueing")
            condemnSessionIfHopeless(peerID, SessionFailureTracker.FailureEvidence.UNDECRYPTABLE)
            return EncryptedPayloadResult.UNREADABLE
        }
    }

    private fun recordDecryptOutcome(peerID: String, via: NoiseEncryptionFacade.DecryptionVia) {
        if (via == NoiseEncryptionFacade.DecryptionVia.ESTABLISHED) {
            sessionFailures.onDecryptSucceeded(peerID)
        } else if (securityManager.hasEstablishedSession(peerID)) {
            // The peer authenticated this payload with the predecessor, proving it has not
            // switched to the established session. Deliver it, but do not let it bless that
            // session or suppress the ordinary recovery lifecycle.
            condemnSessionIfHopeless(peerID, SessionFailureTracker.FailureEvidence.FALLBACK_SUCCESS)
        } else if (!securityManager.isHandshaking(peerID)) {
            // Only the predecessor is left and no handshake is in flight: the one that was owed
            // was lost, abandoned or destroyed, and nothing else would notice, because this traffic
            // still reads. Before the fallback existed these payloads failed to decrypt and that is
            // what asked for the next attempt, so they ask for it still, at the tracker's pace.
            condemnSessionIfHopeless(peerID, SessionFailureTracker.FailureEvidence.UNDECRYPTABLE)
        }
    }

    /**
     * A run of undecryptable payloads or fallback-only proofs means the established session needs
     * recovery. The latter is delivered, but cannot certify that the newer session is shared.
     */
    private fun condemnSessionIfHopeless(peerID: String, evidence: SessionFailureTracker.FailureEvidence) {
        val hasSession = securityManager.hasValidatedSession(peerID)
        if (!hasSession && securityManager.hasCandidate(peerID)) {
            // There is no session to condemn and a handshake is already in flight; its outcome
            // decides, and the sweeper deals with one that stalls.
            return
        }
        val recovery = sessionFailures.onDecryptFailed(
            peerID, clock.now().toEpochMilliseconds(), evidence, hasSession
        ) ?: return

        println("🔁 ${SessionFailureTracker.FAILURES_BEFORE_RECOVERY} consecutive session-recovery " +
            "signals from $peerID; the session needs replacement")
        // Drop this failed run before asking the service to demote and replace the session.
        pendingEncryptedPayloads.drop(peerID)
        when (recovery) {
            SessionFailureTracker.RecoveryReason.UNUSABLE -> delegate?.onSessionUnusable(peerID)
            SessionFailureTracker.RecoveryReason.NOT_SHARED -> delegate?.onSessionNotShared(peerID)
        }
    }

    private fun queueEncryptedMessage(peerID: String, payload: ByteArray): Boolean {
        if (pendingEncryptedPayloads.offer(peerID, payload, clock.now().toEpochMilliseconds())) {
            println("📦 Queued encrypted message from $peerID")
            return true
        }
        return false
    }

    private fun processPendingEncryptedMessages(peerID: String) {
        val queue = pendingEncryptedPayloads.take(peerID, clock.now().toEpochMilliseconds())
        if (queue.isEmpty()) {
            println("📭 No pending messages for $peerID")
            return
        }
        println("📬 Processing ${queue.size} pending encrypted messages for $peerID")
        queue.forEach { payload ->
            handleEncryptedPayload(peerID, payload, requeueOnFailure = false)
        }
        println("✅ Finished processing pending messages for $peerID")
    }

    private enum class EncryptedPayloadResult {
        READ,
        KEPT,
        REFUSED,
        UNREADABLE
    }

    private fun dropAddressedPlaintext(
        packet: BitchatPacket,
        peerID: String,
        type: MessageType,
        reason: String
    ) {
        logInfo(
            "MessageHandler",
            "Dropped addressed plaintext type=${type.name} sender=$peerID " +
                "recipient=${packet.recipientID?.toHexString() ?: "none"} " +
                "length=${packet.payload.size} reason=$reason"
        )
    }

}

interface MessageHandlerDelegate {
    fun onPeerAnnounced(peerID: String, nickname: String)
    fun onMessageReceived(peerID: String, message: String)
    fun onAuthenticatedPrivateMessage(peerID: String, messageId: String, content: String)
    fun onAuthenticatedPrivateFile(peerID: String, file: BitchatFilePacket)
    fun onAuthenticatedDelivered(peerID: String, messageId: String)
    fun onAuthenticatedRead(peerID: String, messageId: String)
    fun onHandshakeReceived(peerID: String)
    fun onHandshakeResponse(peerID: String, responsePacket: ByteArray)
    fun onSessionEstablished(peerID: String)

    /**
     * The session with [peerID] reports itself established but cannot decrypt what that peer sends.
     * Implementations should discard it and start a fresh handshake.
     */
    fun onSessionUnusable(peerID: String)

    /** The peer authenticated traffic with the predecessor while a newer session was established. */
    fun onSessionNotShared(peerID: String)
    fun onPeerLeft(peerID: String)
    fun onFragmentReceived(peerID: String)
    fun onPublicFileReceived(peerID: String, filePacket: BitchatFilePacket)
}
