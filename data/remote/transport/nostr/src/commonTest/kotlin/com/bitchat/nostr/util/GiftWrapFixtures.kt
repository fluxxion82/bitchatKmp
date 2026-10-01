package com.bitchat.nostr.util

import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.transport.IdentityStoreState
import com.bitchat.transport.TransportIdentityProvider
import kotlinx.coroutines.flow.Flow

/** A client for sealing and opening DMs; neither path touches preferences or stored identity. */
internal fun dmClient(): NostrClient = NostrClient(UnusedNostrPreferences, UnusedIdentityProvider)

/** One genuine gift wrap of [content] from [sender] to [recipient], as relays deliver it. */
internal fun NostrClient.giftWrap(content: String, sender: NostrIdentity, recipient: NostrIdentity): NostrEvent =
    createPrivateMessage(content, recipient.publicKeyHex, sender).single()

/** A stand-in gift wrap for tests about bookkeeping, not crypto: pair it with an authenticate step that accepts it. */
internal fun syntheticGiftWrap(id: String, createdAt: Long): NostrEvent = NostrEvent(
    id = id,
    pubkey = "",
    createdAt = createdAt.toInt(),
    kind = NostrKind.GIFT_WRAP,
    tags = emptyList(),
    content = "",
)

private object UnusedNostrPreferences : NostrPreferences {
    override fun getLastUpdateMs(): Long = unused()
    override fun setLastUpdateMs(value: Long) = unused()
    override fun setPowEnabled(enabled: Boolean) = unused()
    override fun getPowEnabled(): Boolean = unused()
    override fun setPowDifficulty(difficulty: Int) = unused()
    override fun getPowDifficulty(): Int = unused()
    override fun setIsMining(isMining: Boolean) = unused()
    override fun getIsMiningFlow(): Flow<Boolean> = unused()
}

private object UnusedIdentityProvider : TransportIdentityProvider {
    override fun loadKey(key: String): String? = unused()
    override fun saveKey(key: String, value: String) = unused()
    override fun hasKey(key: String): Boolean = unused()
    override fun removeKeys(vararg keys: String) = unused()
    override fun clearAll() = unused()
    override fun loadOrMint(key: String, publicFormOf: (String) -> String, mint: () -> String): String = unused()
    override fun storeState(): IdentityStoreState = unused()
}

private fun unused(): Nothing = error("sealing and opening a DM touch neither preferences nor stored identity")
