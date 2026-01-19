package com.bitchat.nostr

import com.bitchat.nostr.model.NostrIdentity

interface NostrClientTest {
    suspend fun initialize()
    fun shutdown()
    suspend fun sendPrivateMessage(
        content: String,
        recipientNpub: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    )

    suspend fun subscribeToPrivateMessages(handler: (content: String, senderNpub: String, timestamp: Int) -> Unit)
    suspend fun sendGeohashMessage(
        content: String,
        geohash: String,
        nickname: String? = null,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
    )

    suspend fun subscribeToGeohash(
        geohash: String,
        handler: (content: String, senderPubkey: String, nickname: String?, timestamp: Int) -> Unit
    )

    suspend fun unsubscribeFromGeohash(geohash: String)
    suspend fun getCurrentIdentity(): NostrIdentity?
}
