package com.bitchat.domain.chat.model

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class BitchatMessage(
    val id: String = Uuid.random().toString().uppercase(),
    val sender: String,
    val content: String,
    val type: BitchatMessageType = BitchatMessageType.Message,
    val timestamp: Instant,
    val isRelay: Boolean = false,
    val originalSender: String? = null,
    val isPrivate: Boolean = false,
    val recipientNickname: String? = null,
    val senderPeerID: String? = null,
    val mentions: List<String>? = null,
    val channel: String? = null,
    val encryptedContent: ByteArray? = null,
    val isEncrypted: Boolean = false,
    val deliveryStatus: DeliveryStatus? = null,
    val powDifficulty: Int? = null,
    val filePacket: BitchatFilePacket? = null,
    val isMining: Boolean = false
)
