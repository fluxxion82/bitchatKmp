package com.bitchat.local.prefs.impl

import com.bitchat.local.prefs.ChannelPreferences
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set

class LocalChannelPreferences(
    settingsFactory: Settings.Factory,
) : ChannelPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)

    override fun getJoinedChannelsList(): Set<String> {
        return try {
            val serialized = settings.getStringOrNull(JOINED_CHANNELS_KEY) ?: return emptySet()
            serialized.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    override fun setJoinedChannel(channelId: String) {
        try {
            val current = getJoinedChannelsList().toMutableSet()
            current.add(channelId.lowercase())
            settings[JOINED_CHANNELS_KEY] = current.joinToString(SEPARATOR)
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun removeJoinedChannel(channelId: String) {
        try {
            val current = getJoinedChannelsList().toMutableSet()
            current.remove(channelId.lowercase())
            settings[JOINED_CHANNELS_KEY] = current.joinToString(SEPARATOR)
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun getSavedProtectedChannels(): Set<String> {
        return try {
            val serialized = settings.getStringOrNull(PROTECTED_CHANNELS_KEY) ?: return emptySet()
            serialized.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    override fun setSavedProtectedChannel(channelId: String) {
        try {
            val current = getSavedProtectedChannels().toMutableSet()
            current.add(channelId.lowercase())
            settings[PROTECTED_CHANNELS_KEY] = current.joinToString(SEPARATOR)
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun removeSavedProtectedChannel(channelId: String) {
        try {
            val current = getSavedProtectedChannels().toMutableSet()
            current.remove(channelId.lowercase())
            settings[PROTECTED_CHANNELS_KEY] = current.joinToString(SEPARATOR)
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun getChannelCreators(): Map<String, String> {
        return try {
            val serialized = settings.getStringOrNull(CHANNEL_CREATORS_KEY) ?: return emptyMap()
            serialized.split(PAIR_SEPARATOR)
                .filter { it.isNotBlank() }
                .mapNotNull { pair ->
                    val parts = pair.split(KEY_VALUE_SEPARATOR)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun setChannelCreator(creatorId: String, channel: String) {
        try {
            val current = getChannelCreators().toMutableMap()
            current[channel.lowercase()] = creatorId
            val serialized = current.map { "${it.key}${KEY_VALUE_SEPARATOR}${it.value}" }
                .joinToString(PAIR_SEPARATOR)
            settings[CHANNEL_CREATORS_KEY] = serialized
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun removeChannelCreator(channelId: String) {
        try {
            val current = getChannelCreators().toMutableMap()
            current.remove(channelId.lowercase())
            val serialized = current.map { "${it.key}${KEY_VALUE_SEPARATOR}${it.value}" }
                .joinToString(PAIR_SEPARATOR)
            settings[CHANNEL_CREATORS_KEY] = serialized
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun getChannelEventIds(): Map<String, String> {
        return try {
            val serialized = settings.getStringOrNull(CHANNEL_EVENT_IDS_KEY) ?: return emptyMap()
            serialized.split(PAIR_SEPARATOR)
                .filter { it.isNotBlank() }
                .mapNotNull { pair ->
                    val parts = pair.split(KEY_VALUE_SEPARATOR)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun setChannelEventId(channelId: String, eventId: String) {
        try {
            val current = getChannelEventIds().toMutableMap()
            current[channelId.lowercase()] = eventId
            val serialized = current.map { "${it.key}${KEY_VALUE_SEPARATOR}${it.value}" }
                .joinToString(PAIR_SEPARATOR)
            settings[CHANNEL_EVENT_IDS_KEY] = serialized
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun removeChannelEventId(channelId: String) {
        try {
            val current = getChannelEventIds().toMutableMap()
            current.remove(channelId.lowercase())
            val serialized = current.map { "${it.key}${KEY_VALUE_SEPARATOR}${it.value}" }
                .joinToString(PAIR_SEPARATOR)
            settings[CHANNEL_EVENT_IDS_KEY] = serialized
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun clearJoinedChannels() {
        settings.remove(JOINED_CHANNELS_KEY)
    }

    override fun clearProtectedChannels() {
        settings.remove(PROTECTED_CHANNELS_KEY)
    }

    override fun clearChannelEventIds() {
        settings.remove(CHANNEL_EVENT_IDS_KEY)
    }

    companion object {
        private const val PREFS_NAME = "channel_preferences"

        private const val JOINED_CHANNELS_KEY = "joined_channels"
        private const val PROTECTED_CHANNELS_KEY = "protected_channels"
        private const val CHANNEL_CREATORS_KEY = "channel_creators"
        private const val CHANNEL_EVENT_IDS_KEY = "channel_event_ids"

        private const val SEPARATOR = ","
        private const val PAIR_SEPARATOR = ";"
        private const val KEY_VALUE_SEPARATOR = ":"
    }
}
