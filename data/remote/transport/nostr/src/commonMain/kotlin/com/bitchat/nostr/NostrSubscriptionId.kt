package com.bitchat.nostr

/** NIP-01 permits subscription IDs no longer than 64 characters. */
object NostrSubscriptionId {
    const val MAX_LENGTH = 64

    fun geohash(geohash: String): String = withPayload("geohash_", geohash)

    fun geohashMessages(geohash: String): String = withPayload("geohash_messages_", geohash)

    fun sampling(geohash: String): String = withPayload("sampling_", geohash)

    fun directMessages(pubkey: String): String = withPayload("dm_", pubkey)

    fun geohashDirectMessages(geohash: String): String = withPayload("geodm_", geohash)

    fun notes(geohash: String, index: Int): String = withPayload("notes_", "${geohash}_$index")

    fun channelMessages(channelEventId: String): String = withPayload("chan_", channelEventId.take(16))

    fun channelCreations(channelName: String): String = withPayload("channel_create_", channelName.hashCode().toString())

    fun allChannelCreations(): String = "channel_create_all"

    private fun withPayload(prefix: String, payload: String): String =
        prefix + payload.take(MAX_LENGTH - prefix.length)
}
