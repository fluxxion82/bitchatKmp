package com.bitchat.api.dto.chat

data class ReadReceipt(
    val originalMessageID: String,
    val readerPeerID: String? = null
)