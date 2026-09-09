package com.bitchat.bluetooth.handler

import com.bitchat.api.dto.mapper.toBitchatFilePacket
import com.bitchat.bluetooth.facade.CryptoSigningFacade
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
import com.bitchat.crypto.Cryptography
import kotlin.time.Clock
import com.bitchat.domain.chat.model.BitchatFilePacket
import com.bitchat.noise.model.NoisePayload
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.noise.model.PrivateMessagePacket

class MessageHandler(
    private val myPeerID: String,
    private val securityManager: SecurityManager,
    private val peerManager: PeerManager,
    private val cryptoSigning: CryptoSigningFacade
) {
    var delegate: MessageHandlerDelegate? = null

    private val pendingEncryptedMessages = mutableMapOf<String, MutableList<ByteArray>>()

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
        if (peerID == myPeerID) {
            logDebug("MessageHandler", "Ignoring self-message (local echo exists)")
            return
        }

        val messageText = packet.payload.decodeToString()
        val isBroadcast = packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)
        delegate?.onMessageReceived(peerID, messageText, isBroadcast = isBroadcast)
    }

    private suspend fun handleNoiseHandshake(packet: BitchatPacket, peerID: String) {
        println("🔐 NOISE_HANDSHAKE: Processing handshake from $peerID")

        val localPrivateKey = cryptoSigning.getNoisePrivateKey()
        val localPublicKey = cryptoSigning.getNoisePublicKey()

        val responsePacket = securityManager.handleNoiseHandshake(
            packet = packet,
            peerID = peerID,
            localPrivateKey = localPrivateKey,
            localPublicKey = localPublicKey
        )

        if (responsePacket != null) {
            println("🔐 NOISE_HANDSHAKE: Generated response packet (${responsePacket.size} bytes)")
            delegate?.onHandshakeResponse(peerID, responsePacket)
        }

        if (securityManager.hasEstablishedSession(peerID)) {
            val remoteStaticKey = securityManager.getRemoteStaticKey(peerID)
            if (remoteStaticKey != null) {
                val fingerprint = calculateSHA256Fingerprint(remoteStaticKey)
                println("✅ NOISE_HANDSHAKE: Session established with $peerID")
                println("📍 NOISE_HANDSHAKE: Remote static key fingerprint: $fingerprint")
                println("   Remote static key (hex): ${remoteStaticKey.toHexString()}")
            } else {
                println("✅ NOISE_HANDSHAKE: Session established with $peerID")
            }
            delegate?.onSessionEstablished(peerID)
            processPendingEncryptedMessages(peerID)
        } else {
            println("⏳ NOISE_HANDSHAKE: Session not yet established with $peerID")
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

        handleEncryptedPayload(peerID, packet.payload, requeueOnFailure = true)
    }

    private fun handleLeave(packet: BitchatPacket, peerID: String) {
        peerManager.disconnectPeer(peerID)
        delegate?.onPeerLeft(peerID)
    }

    private fun handleFragment(packet: BitchatPacket, peerID: String) {
        delegate?.onFragmentReceived(peerID)
    }

    private fun handleFileTransfer(packet: BitchatPacket, peerID: String) {
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

        logInfo("MessageHandler", "📎 File: ${filePacket.fileName} (${filePacket.fileSize} bytes, ${filePacket.mimeType})")

        val isBroadcast = packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)
        delegate?.onFileReceived(peerID, filePacket, isBroadcast = isBroadcast)
    }

    private fun handleEncryptedPayload(peerID: String, payload: ByteArray, requeueOnFailure: Boolean) {
        val decrypted = securityManager.decryptFromPeer(peerID, payload)
        if (decrypted != null) {
            val noisePayload = NoisePayload.decode(decrypted)
            if (noisePayload == null) {
                println("❌ Failed to parse NoisePayload from $peerID")
                return
            }

            when (noisePayload.type) {
                NoisePayloadType.PRIVATE_MESSAGE -> {
                    val privateMessage = PrivateMessagePacket.decode(noisePayload.data)
                    if (privateMessage != null) {
                        println("✅ Decrypted message from $peerID: ${privateMessage.content.take(50)}")
                        delegate?.onEncryptedMessageReceived(peerID, privateMessage.content)
                    } else {
                        println("❌ Failed to parse PrivateMessagePacket from $peerID")
                    }
                }

                NoisePayloadType.READ_RECEIPT -> {
                    // TODO: Handle read receipts
                    println("📖 Read receipt from $peerID")
                }

                NoisePayloadType.DELIVERED -> {
                    // TODO: Handle delivery confirmations
                    println("✓ Delivery confirmation from $peerID")
                }

                NoisePayloadType.FILE_TRANSFER -> {
                    val filePacket = noisePayload.data.toBitchatFilePacket()
                    if (filePacket != null) {
                        logInfo("MessageHandler", "📎 Received encrypted file from $peerID: ${filePacket.fileName} (${filePacket.fileSize} bytes)")
                        delegate?.onFileReceived(peerID, filePacket, isBroadcast = false)
                    } else {
                        logError("MessageHandler", "❌ Failed to decode encrypted file from $peerID")
                    }
                }
            }
            sessionFailures.onDecryptSucceeded(peerID)
        } else if (requeueOnFailure) {
            println("⏳ Failed to decrypt from $peerID, queueing for retry")
            queueEncryptedMessage(peerID, payload)
            condemnSessionIfHopeless(peerID)
        } else {
            println("❌ Failed to decrypt from $peerID, not requeueing")
            condemnSessionIfHopeless(peerID)
        }
    }

    /**
     * A run of failures means the session is wrong, not the packets. Nothing used to notice: the
     * facade returned null, the payload was queued, and direct messages from that peer stopped for
     * good while the session still reported itself established.
     */
    private fun condemnSessionIfHopeless(peerID: String) {
        if (!sessionFailures.onDecryptFailed(peerID, Clock.System.now().toEpochMilliseconds())) return

        println("🔁 ${SessionFailureTracker.FAILURES_BEFORE_RECOVERY} consecutive decryption " +
            "failures from $peerID; the session cannot be the right one, rebuilding it")
        // Anything queued was encrypted under the session being discarded, so it can never decrypt.
        pendingEncryptedMessages.remove(peerID)
        delegate?.onSessionUnusable(peerID)
    }

    private fun queueEncryptedMessage(peerID: String, payload: ByteArray) {
        val queue = pendingEncryptedMessages.getOrPut(peerID) { mutableListOf() }
        queue.add(payload)
        println("📦 Queued encrypted message from $peerID (queue size: ${queue.size})")
    }

    private fun processPendingEncryptedMessages(peerID: String) {
        val queue = pendingEncryptedMessages.remove(peerID) ?: run {
            println("📭 No pending messages for $peerID")
            return
        }
        println("📬 Processing ${queue.size} pending encrypted messages for $peerID")
        queue.forEach { payload ->
            handleEncryptedPayload(peerID, payload, requeueOnFailure = false)
        }
        println("✅ Finished processing pending messages for $peerID")
    }

    private fun calculateSHA256Fingerprint(publicKey: ByteArray): String {
        val hash = Cryptography.getDigestHash(publicKey)
        return hash.joinToString("") { it.toHexString() }
    }
}

interface MessageHandlerDelegate {
    fun onPeerAnnounced(peerID: String, nickname: String)
    fun onMessageReceived(peerID: String, message: String, isBroadcast: Boolean)
    fun onEncryptedMessageReceived(peerID: String, message: String)
    fun onHandshakeReceived(peerID: String)
    fun onHandshakeResponse(peerID: String, responsePacket: ByteArray)
    fun onSessionEstablished(peerID: String)

    /**
     * The session with [peerID] reports itself established but cannot decrypt what that peer sends.
     * Implementations should discard it and start a fresh handshake.
     */
    fun onSessionUnusable(peerID: String)
    fun onPeerLeft(peerID: String)
    fun onFragmentReceived(peerID: String)
    fun onFileReceived(peerID: String, filePacket: BitchatFilePacket, isBroadcast: Boolean)
}
