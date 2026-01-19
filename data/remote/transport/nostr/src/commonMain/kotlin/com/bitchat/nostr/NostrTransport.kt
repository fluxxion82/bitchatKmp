package com.bitchat.nostr

import com.bitchat.api.dto.chat.ReadReceipt
import com.bitchat.noise.model.NoisePayloadType
import com.bitchat.nostr.model.NostrIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class NostrTransport(
    var senderPeerID: String = "",
    val nostrClient: NostrClient,
    val nostrRelay: NostrRelay,
) {
    private val transportScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun sendPrivateMessage(
        content: String,
        recipientNostrPubkey: String,
        recipientPeerID: String,
        messageID: String,
        recipientNickname: String = ""
    ) {
        transportScope.launch {
            try {
                if (recipientNostrPubkey.isEmpty() || recipientPeerID.isEmpty()) {
                    // Log.w(TAG, "NostrTransport: recipientNostrPubkey or recipientPeerID is empty")
                    return@launch
                }

                val senderIdentity = nostrClient.getCurrentNostrIdentity()
                if (senderIdentity == null) {
                    // Log.e(TAG, "No Nostr identity available")
                    return@launch
                }

                // Log.d(TAG, "NostrTransport: preparing PM to ${recipientNostrPubkey.take(16)}... peerID=$recipientPeerID id=${messageID.take(8)}...")

                // Convert recipient npub -> hex (x-only)
                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") {
                        // Log.e(TAG, "NostrTransport: recipient key not npub (hrp=$hrp)")
                        return@launch
                    }
                    data.toHexString()
                } catch (e: Exception) {
                    // Log.e(TAG, "NostrTransport: failed to decode npub -> hex: $e")
                    return@launch
                }

                val embedded = NostrEmbeddedBitChat.encodePMForNostr(
                    content = content,
                    messageID = messageID,
                    recipientPeerID = recipientPeerID,
                    senderPeerID = senderPeerID
                )

                if (embedded == null) {
                    // Log.e(TAG, "NostrTransport: failed to embed PM packet")
                    return@launch
                }

                val giftWraps = nostrClient.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )

                giftWraps.forEach { event ->
                    // Log.d(TAG, "NostrTransport: sending PM giftWrap id=${event.id.take(16)}...")
                    nostrRelay.sendEvent(event)
                }

            } catch (e: Exception) {
                // Log.e(TAG, "Failed to send private message via Nostr: ${e.message}")
            }
        }
    }

    fun sendReadReceipt(
        receipt: ReadReceipt,
        recipientPeerID: String,
        recipientNostrPubkey: String
    ) {
        transportScope.launch {
            try {
                if (recipientNostrPubkey.isEmpty() || recipientPeerID.isEmpty()) {
                    // Log.w(TAG, "NostrTransport: recipientNostrPubkey or recipientPeerID is empty")
                    return@launch
                }

                val senderIdentity = nostrClient.getCurrentNostrIdentity()
                if (senderIdentity == null) {
                    // Log.e(TAG, "No Nostr identity available for read receipt")
                    return@launch
                }

                // Log.d(TAG, "NostrTransport: preparing READ ack for id=${receipt.originalMessageID.take(8)}...")

                // Convert recipient npub -> hex
                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") {
                        return@launch
                    }
                    data.toHexString()
                } catch (e: Exception) {
                    return@launch
                }

                val ack = NostrEmbeddedBitChat.encodeAckForNostr(
                    type = NoisePayloadType.READ_RECEIPT,
                    messageID = receipt.originalMessageID,
                    recipientPeerID = recipientPeerID,
                    senderPeerID = senderPeerID
                )

                if (ack == null) {
                    // Log.e(TAG, "NostrTransport: failed to embed READ ack")
                    return@launch
                }

                val giftWraps = nostrClient.createPrivateMessage(
                    content = ack,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )

                giftWraps.forEach { event ->
                    // Log.d(TAG, "NostrTransport: sending READ ack giftWrap id=${event.id.take(16)}...")
                    nostrRelay.sendEvent(event)
                }

            } catch (e: Exception) {
                // Log.e(TAG, "Failed to send read receipt via Nostr: ${e.message}")
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun sendFavoriteNotification(
        recipientPeerID: String,
        recipientNostrPubkey: String,
        isFavorite: Boolean
    ) {
        transportScope.launch {
            try {
                if (recipientNostrPubkey.isEmpty() || recipientPeerID.isEmpty()) {
                    // Log.w(TAG, "NostrTransport: recipientNostrPubkey or recipientPeerID is empty")
                    return@launch
                }

                val senderIdentity = nostrClient.getCurrentNostrIdentity()
                if (senderIdentity == null) {
                    // Log.e(TAG, "No Nostr identity available for favorite notification")
                    return@launch
                }

                val content = if (isFavorite) "[FAVORITED]:${senderIdentity.npub}" else "[UNFAVORITED]:${senderIdentity.npub}"

                // Log.d(TAG, "NostrTransport: preparing FAVORITE($isFavorite) to ${recipientNostrPubkey.take(16)}...")

                // Convert recipient npub -> hex
                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") return@launch
                    data.toHexString()
                } catch (e: Exception) {
                    return@launch
                }

                val embedded = NostrEmbeddedBitChat.encodePMForNostr(
                    content = content,
                    messageID = Uuid.random().toString(),
                    recipientPeerID = recipientPeerID,
                    senderPeerID = senderPeerID
                )

                if (embedded == null) {
                    // Log.e(TAG, "NostrTransport: failed to embed favorite notification")
                    return@launch
                }

                val giftWraps = nostrClient.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )

                giftWraps.forEach { event ->
                    // Log.d(TAG, "NostrTransport: sending favorite giftWrap id=${event.id.take(16)}...")
                    nostrRelay.sendEvent(event)
                }

            } catch (e: Exception) {
                // Log.e(TAG, "Failed to send favorite notification via Nostr: ${e.message}")
            }
        }
    }

    fun sendDeliveryAck(
        messageID: String,
        recipientPeerID: String,
        recipientNostrPubkey: String
    ) {
        transportScope.launch {
            try {
                if (recipientNostrPubkey.isEmpty() || recipientPeerID.isEmpty()) {
                    // Log.w(TAG, "NostrTransport: recipientNostrPubkey or recipientPeerID is empty")
                    return@launch
                }

                val senderIdentity = nostrClient.getCurrentNostrIdentity()
                if (senderIdentity == null) {
                    // Log.e(TAG, "No Nostr identity available for delivery ack")
                    return@launch
                }

                // Log.d(TAG, "NostrTransport: preparing DELIVERED ack for id=${messageID.take(8)}...")

                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") return@launch
                    data.toHexString()
                } catch (e: Exception) {
                    return@launch
                }

                val ack = NostrEmbeddedBitChat.encodeAckForNostr(
                    type = NoisePayloadType.DELIVERED,
                    messageID = messageID,
                    recipientPeerID = recipientPeerID,
                    senderPeerID = senderPeerID
                )

                if (ack == null) {
                    // Log.e(TAG, "NostrTransport: failed to embed DELIVERED ack")
                    return@launch
                }

                val giftWraps = nostrClient.createPrivateMessage(
                    content = ack,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )

                giftWraps.forEach { event ->
                    // Log.d(TAG, "NostrTransport: sending DELIVERED ack giftWrap id=${event.id.take(16)}...")
                    nostrRelay.sendEvent(event)
                }

            } catch (e: Exception) {
                // Log.e(TAG, "Failed to send delivery ack via Nostr: ${e.message}")
            }
        }
    }

    // MARK: - Geohash ACK helpers (for per-geohash identity DMs)

    fun sendDeliveryAckGeohash(
        messageID: String,
        toRecipientHex: String,
        fromIdentity: NostrIdentity
    ) {
        transportScope.launch {
            try {
                // Log.d(TAG, "GeoDM: send DELIVERED -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}...")

                val embedded = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(
                    type = NoisePayloadType.DELIVERED,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )

                if (embedded == null) return@launch

                val giftWraps = nostrClient.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )

                // Register pending gift wrap for deduplication and send all
                giftWraps.forEach { event ->
                    nostrRelay.registerPendingGiftWrap(event.id)
                    nostrRelay.sendEvent(event)
                }

            } catch (e: Exception) {
                // Log.e(TAG, "Failed to send geohash delivery ack: ${e.message}")
            }
        }
    }

    fun sendReadReceiptGeohash(
        messageID: String,
        toRecipientHex: String,
        fromIdentity: NostrIdentity
    ) {
        transportScope.launch {
            try {
                // Log.d(TAG, "GeoDM: send READ -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}...")

                val embedded = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(
                    type = NoisePayloadType.READ_RECEIPT,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )

                if (embedded == null) return@launch

                val giftWraps = nostrClient.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )

                // Register pending gift wrap for deduplication and send all
                giftWraps.forEach { event ->
                    nostrRelay.registerPendingGiftWrap(event.id)
                    nostrRelay.sendEvent(event)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                // Log.e(TAG, "Failed to send geohash read receipt: ${e.message}")
            }
        }
    }

    // MARK: - Geohash DMs (per-geohash identity)

    fun sendPrivateMessageGeohash(
        content: String,
        toRecipientHex: String,
        messageID: String,
        sourceGeohash: String
    ) {
        require(sourceGeohash.isNotEmpty()) { "sourceGeohash must not be empty" }
        require(toRecipientHex.isNotEmpty()) { "toRecipientHex must not be empty" }

        val fromIdentity = try {
            nostrClient.deriveIdentity(sourceGeohash)
        } catch (e: Exception) {
            // Log.e(TAG, "NostrTransport: cannot derive geohash identity for $sourceGeohash: ${e.message}")
            return
        }

        transportScope.launch {
            try {
                // Log.d(TAG, "GeoDM: send PM -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}... geohash=$sourceGeohash")

                // Build embedded BitChat packet without recipient peer ID
                val embedded = NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                    content = content,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                ) ?: run {
                    // Log.e(TAG, "NostrTransport: failed to embed geohash PM packet")
                    return@launch
                }

                val giftWraps = nostrClient.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )

                giftWraps.forEach { event ->
                    // Log.d(TAG, "NostrTransport: sending geohash PM giftWrap id=${event.id.take(16)}...")
                    nostrRelay.registerPendingGiftWrap(event.id)
                    nostrRelay.sendEvent(event)
                }
            } catch (e: Exception) {
                // Log.e(TAG, "Failed to send geohash private message: ${e.message}")
            }
        }
    }

    fun cleanup() {
        transportScope.cancel()
    }
}
