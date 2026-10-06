package com.bitchat.repo.utils

import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.nostr.Bech32

internal const val MAX_NOT_FAVOURITED_RELATIONSHIPS = 200

internal class FavoriteNotification(val isFavorite: Boolean, val claimedNpub: String?) {
    companion object {
        fun parse(content: String): FavoriteNotification? {
            val normalized = content.trim()
            val isFavorite = when {
                normalized.startsWith("[FAVORITED]:") -> true
                normalized.startsWith("[UNFAVORITED]:") -> false
                else -> return null
            }
            return FavoriteNotification(isFavorite, normalized.substringAfter(':').trim().takeIf { it.isNotEmpty() })
        }
    }
}

/** What a notification changes: the record to write, if any, and the keys of the records to delete. */
internal class FavoriteChange(val saved: FavoriteRelationship?, val removedKeys: List<String>)

/**
 * What one "[FAVORITED]" / "[UNFAVORITED]" notification changes in the saved [favorites].
 *
 * Over Nostr ([senderNpub] is the key the message arrived from) it can only update a relationship that
 * is already there: the record under [senderKey], unless that one is tied to another Nostr key, or
 * else a record tied to this Nostr key (a mesh favourite notifying while out of range). The key
 * written in the message is ignored. A Nostr key costs nothing to make, so one the app knows nothing
 * about leaves nothing behind.
 *
 * Over the mesh ([senderNpub] is null; [senderKey] is a peer id proven by its Noise session) a peer the
 * user has not favourited may be recorded as having favourited them, and may say which Nostr key is
 * its own, if that is a well-formed npub. Such records are capped ([notFavouritedOverLimit]).
 *
 * A record that would say neither side favourites the other is removed instead. The user's own choice
 * (`isFavorite`) and the name saved with a record are never changed here. The caller does the
 * read-modify-write under its lock.
 */
internal fun applyFavoriteNotification(
    favorites: Map<String, FavoriteRelationship>,
    notification: FavoriteNotification,
    senderKey: String,
    senderNpub: String?,
    senderName: String,
    now: Long,
    maxNotFavourited: Int = MAX_NOT_FAVOURITED_RELATIONSHIPS,
): FavoriteChange {
    val key = senderKey.lowercase()
    val existing = if (senderNpub != null) {
        val direct = favorites[key]?.takeIf { it.peerNostrPublicKey == null || it.peerNostrPublicKey.equals(senderNpub, true) }
        val tied = favorites.values.filter { it !== direct && it.peerNostrPublicKey.equals(senderNpub, true) }
        // More than one record can stand for this key: an older version saved one under the Nostr key
        // itself for anyone who sent a notification, and a mesh peer may claim someone else's key for
        // its own record. The one the user chose to favourite is the one meant; failing that, the
        // record under the sender's own key, then the one recorded first.
        val candidates = listOfNotNull(direct) + tied
        candidates.firstOrNull { it.isFavorite } ?: candidates.firstOrNull()
    } else {
        favorites[key]
    }

    val saved = if (senderNpub != null) {
        existing?.copy(
            peerNostrPublicKey = senderNpub,
            theyFavoritedUs = notification.isFavorite,
            lastUpdated = now,
        )
    } else {
        val claimedNpub = notification.claimedNpub?.takeIf(::isValidNpub)
        when {
            existing != null -> existing.copy(
                peerNostrPublicKey = claimedNpub ?: existing.peerNostrPublicKey,
                theyFavoritedUs = notification.isFavorite,
                lastUpdated = now,
            )

            notification.isFavorite -> FavoriteRelationship(
                peerNoisePublicKeyHex = key,
                peerNostrPublicKey = claimedNpub,
                peerNickname = senderName,
                isFavorite = false,
                theyFavoritedUs = true,
                favoritedAt = now,
                lastUpdated = now,
            )

            else -> null
        }
    }

    if (saved == null) return FavoriteChange(null, emptyList())
    // Said again, it changes nothing and is not worth a write: a peer repeating itself must not be
    // able to make this device rewrite its preferences each time.
    if (existing != null && saved.copy(lastUpdated = existing.lastUpdated) == existing) return FavoriteChange(null, emptyList())
    val savedKey = saved.peerNoisePublicKeyHex.lowercase()
    if (!saved.isFavorite && !saved.theyFavoritedUs) return FavoriteChange(null, listOf(savedKey))

    val updated = favorites.toMutableMap().apply { this[savedKey] = saved }
    return FavoriteChange(saved, notFavouritedOverLimit(updated, maxNotFavourited, savedKey))
}

/**
 * The keys of the records to remove so that at most [max] remain of those the user has NOT favourited
 * ("they favourited you" only): the ones this device recorded longest ago (`lastUpdated` is this
 * device's clock, not a sender's field), never [keep], never a record the user favourited.
 */
internal fun notFavouritedOverLimit(
    favorites: Map<String, FavoriteRelationship>,
    max: Int,
    keep: String? = null,
): List<String> {
    val incomingOnly = favorites.entries
        .filter { (_, favorite) -> !favorite.isFavorite }
        .sortedBy { (_, favorite) -> favorite.lastUpdated }
    val excess = incomingOnly.size - max
    if (excess <= 0) return emptyList()
    val removable = incomingOnly.filter { it.key != keep }
    return removable.take(excess).map { it.key }
}

private fun isValidNpub(value: String): Boolean = runCatching {
    val (hrp, bytes) = Bech32.decode(value)
    hrp == "npub" && bytes.size == 32
}.getOrDefault(false)
