package com.bitchat.viewvo.chat

import com.bitchat.domain.chat.model.BitchatMessage

data class DmState(
    val privateChats: Map<String, List<BitchatMessage>> = emptyMap(),
    val selectedPeer: String? = null,
    val unreadPeers: Set<String> = emptySet(),
    val latestUnreadPeer: String? = null,
    val messageInput: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null
)
