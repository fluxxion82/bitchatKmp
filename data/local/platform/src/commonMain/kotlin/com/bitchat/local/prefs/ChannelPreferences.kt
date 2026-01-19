package com.bitchat.local.prefs

interface ChannelPreferences {
    fun getJoinedChannelsList(): Set<String>
    fun setJoinedChannel(channelId: String)
    fun removeJoinedChannel(channelId: String)
    fun getSavedProtectedChannels(): Set<String>
    fun setSavedProtectedChannel(channelId: String)
    fun removeSavedProtectedChannel(channelId: String)

    fun getChannelCreators(): Map<String, String>
    fun setChannelCreator(creatorId: String, channel: String)
    fun removeChannelCreator(channelId: String)

    // Channel Nostr event IDs (kind 40 event IDs)
    fun getChannelEventIds(): Map<String, String>
    fun setChannelEventId(channelId: String, eventId: String)
    fun removeChannelEventId(channelId: String)

    // Clear methods
    fun clearJoinedChannels()
    fun clearProtectedChannels()
    fun clearChannelEventIds()
}
