package com.bitchat.local.prefs.impl

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.BatteryOptimizationStatus
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.user.model.AppUser
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.UserPreferences
import com.russhwolf.settings.set
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class LocalUserPreferences(
    encryptedPreferenceFactory: EncryptionSettingsFactory,
) : UserPreferences {
    private val settings = encryptedPreferenceFactory.createEncrypted(PREFERENCES_NAME)

    override fun getAppUser(): AppUser {
        val savedName = settings.getStringOrNull(USER_NAME)
        return if (savedName != null) {
            AppUser.ActiveAnonymous(name = savedName)
        } else {
            val randomName = "anon${kotlin.random.Random.nextInt(1000, 9999)}"
            settings[USER_NAME] = randomName
            AppUser.ActiveAnonymous(name = randomName)
        }
    }

    override fun upsertAppUser(user: AppUser) {
        when (user) {
            AppUser.Anonymous -> Unit
            is AppUser.ActiveAnonymous -> {
                settings[USER_NAME] = user.name
            }
        }
    }

    private fun parseChannelString(channelString: String?): Channel? {
        if (channelString == null) return null
        return when {
            channelString == "mesh" -> Channel.Mesh
            channelString.startsWith("named:") -> {
                val channelName = channelString.removePrefix("named:")
                Channel.NamedChannel(channelName)
            }

            channelString.startsWith("meshDM:") -> {
                val parts = channelString.removePrefix("meshDM:").split("|")
                Channel.MeshDM(
                    peerID = parts.getOrNull(0) ?: return null,
                    displayName = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                )
            }

            channelString.startsWith("nostrDM:") -> {
                val parts = channelString.removePrefix("nostrDM:").split("|")
                Channel.NostrDM(
                    peerID = parts.getOrNull(0) ?: return null,
                    fullPubkey = parts.getOrNull(1) ?: return null,
                    sourceGeohash = parts.getOrNull(2)?.takeIf { it != "null" },
                    displayName = parts.getOrNull(3)?.takeIf { it != "null" }
                )
            }

            channelString.startsWith("dm:") -> {
                // Legacy migration: treat old "dm:" as MeshDM
                val peerID = channelString.removePrefix("dm:")
                Channel.MeshDM(peerID = peerID, displayName = null)
            }

            else -> {
                Channel.Location(
                    level = GeohashChannelLevel.CITY,
                    geohash = channelString
                )
            }
        }
    }

    private fun serializeChannel(channel: Channel?): String? {
        return when (channel) {
            null -> null
            Channel.Mesh -> "mesh"
            is Channel.Location -> channel.geohash
            is Channel.NamedChannel -> "named:${channel.channelName}"
            is Channel.MeshDM -> "meshDM:${channel.peerID}|${channel.displayName ?: ""}"
            is Channel.NostrDM -> "nostrDM:${channel.peerID}|${channel.fullPubkey}|${channel.sourceGeohash ?: "null"}|${channel.displayName ?: "null"}"
        }
    }

    override fun getUserState(): UserState? {
        val userState = settings.getStringOrNull(USER_STATE_KEY)
        val loggedInState = settings.getStringOrNull(ACTIVE_STATE_KEY)
        val stateId = settings.getStringOrNull(STATE_ID_KEY)

        println("prefs get user state, userState:$userState, loggedInState:$loggedInState")
        return when (userState) {
            ACTIVE -> {
                when (loggedInState) {
                    CHAT -> {
                        val channelString = stateId ?: return null
                        val channel = parseChannelString(channelString) ?: return null

                        val previousChannelString = settings.getStringOrNull(PREVIOUS_CHANNEL_KEY)
                        val previousChannel = parseChannelString(previousChannelString) ?: Channel.Mesh

                        UserState.Active(ActiveState.Chat(channel, previousChannel))
                    }

                    LOCATIONS -> UserState.Active(ActiveState.Locations)

                    SETTINGS -> UserState.Active(ActiveState.Settings)

                    LOCATION_NOTES -> {
                        val previousChannelString = settings.getStringOrNull(PREVIOUS_CHANNEL_KEY)
                        val previousChannel = parseChannelString(previousChannelString) ?: Channel.Mesh

                        UserState.Active(ActiveState.LocationNotes(previousChannel))
                    }

                    else -> null
                }
            }

            BATTERY_OPTIMIZATION -> {
                val statusString = stateId
                val status = when (statusString) {
                    "ENABLED" -> BatteryOptimizationStatus.ENABLED
                    "DISABLED" -> BatteryOptimizationStatus.DISABLED
                    "NOT_SUPPORTED" -> BatteryOptimizationStatus.NOT_SUPPORTED
                    else -> BatteryOptimizationStatus.ENABLED // default
                }
                UserState.BatteryOptimization(status)
            }

            else -> null
        }
    }

    override fun setUserState(state: UserState) {
        println("prefs set user state: $state")
        when (state) {
            UserState.BluetoothDisabled,
            UserState.LocationServicesDisabled,
            UserState.PermissionsRequired -> {
                settings[USER_STATE_KEY] = null
                settings[ACTIVE_STATE_KEY] = null
                settings[STATE_ID_KEY] = null
            }

            is UserState.Active -> {
                settings[USER_STATE_KEY] = ACTIVE
                when (val loggedInState = state.activeState) {
                    is ActiveState.Chat -> {
                        settings[ACTIVE_STATE_KEY] = CHAT
                        settings[STATE_ID_KEY] = serializeChannel(loggedInState.channel)
                        settings[PREVIOUS_CHANNEL_KEY] = serializeChannel(loggedInState.previousChannel)
                    }

                    ActiveState.Locations -> {
                        settings[ACTIVE_STATE_KEY] = LOCATIONS
                    }

                    ActiveState.Settings -> {
                        settings[ACTIVE_STATE_KEY] = SETTINGS
                    }

                    is ActiveState.LocationNotes -> {
                        settings[ACTIVE_STATE_KEY] = LOCATION_NOTES
                        settings[PREVIOUS_CHANNEL_KEY] = serializeChannel(loggedInState.previousChannel)
                    }
                }
            }

            is UserState.BatteryOptimization -> {
                settings[ACTIVE_STATE_KEY] = null
                settings[USER_STATE_KEY] = BATTERY_OPTIMIZATION
                settings[STATE_ID_KEY] = state.status.name
            }
        }
    }

    // Favorite management
    override fun getAllFavorites(): Map<String, FavoriteRelationship> {
        return try {
            val json = settings.getStringOrNull(FAVORITES_KEY) ?: return emptyMap()
            val serializer = MapSerializer(String.serializer(), FavoriteRelationship.serializer())
            val data = Json.decodeFromString(serializer, json)
            data.mapValues { (_, relationshipData) -> relationshipData }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun getFavorite(noisePublicKeyHex: String): FavoriteRelationship? {
        return getAllFavorites()[noisePublicKeyHex.lowercase()]
    }

    override fun saveFavorite(favorite: FavoriteRelationship) {
        try {
            val allFavorites = getAllFavorites().toMutableMap()
            allFavorites[favorite.peerNoisePublicKeyHex.lowercase()] = favorite

            val serializer = MapSerializer(String.serializer(), FavoriteRelationship.serializer())
            val data = allFavorites.mapValues { (_, rel) -> rel }
            val json = Json.encodeToString(serializer, data)
            settings[FAVORITES_KEY] = json
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun deleteFavorite(noisePublicKeyHex: String) {
        try {
            val allFavorites = getAllFavorites().toMutableMap()
            allFavorites.remove(noisePublicKeyHex.lowercase())

            val serializer = MapSerializer(String.serializer(), FavoriteRelationship.serializer())
            val data = allFavorites.mapValues { (_, rel) -> rel }
            val json = Json.encodeToString(serializer, data)
            settings[FAVORITES_KEY] = json
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun clearAllFavorites() {
        settings[FAVORITES_KEY] = null
    }

    override fun getNostrPubkeyForPeerID(peerID: String): String? {
        return getAllPeerIDMappings()[peerID.lowercase()]
    }

    override fun setNostrPubkeyForPeerID(peerID: String, nostrPubkey: String) {
        try {
            val allMappings = getAllPeerIDMappings().toMutableMap()
            allMappings[peerID.lowercase()] = nostrPubkey

            val serializer = MapSerializer(String.serializer(), String.serializer())
            val json = Json.encodeToString(serializer, allMappings)
            settings[PEERID_INDEX_KEY] = json
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun getAllPeerIDMappings(): Map<String, String> {
        return try {
            val json = settings.getStringOrNull(PEERID_INDEX_KEY) ?: return emptyMap()
            val serializer = MapSerializer(String.serializer(), String.serializer())
            Json.decodeFromString(serializer, json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun clearAllPeerIDMappings() {
        settings[PEERID_INDEX_KEY] = null
    }

    override fun getPeerDisplayName(peerID: String): String? {
        return getAllPeerDisplayNames()[peerID.lowercase()]
    }

    override fun setPeerDisplayName(peerID: String, displayName: String) {
        try {
            val all = getAllPeerDisplayNames().toMutableMap()
            all[peerID.lowercase()] = displayName

            val serializer = MapSerializer(String.serializer(), String.serializer())
            val json = Json.encodeToString(serializer, all)
            settings[PEER_DISPLAY_NAME_KEY] = json
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun getAllPeerDisplayNames(): Map<String, String> {
        return try {
            val json = settings.getStringOrNull(PEER_DISPLAY_NAME_KEY) ?: return emptyMap()
            val serializer = MapSerializer(String.serializer(), String.serializer())
            Json.decodeFromString(serializer, json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun clearPeerDisplayNames() {
        settings[PEER_DISPLAY_NAME_KEY] = null
    }

    // Last-read timestamps for private conversations
    override fun getLastReadTimestamp(peerID: String): Long? {
        return getAllLastReadTimestamps()[peerID.lowercase()]
    }

    override fun setLastReadTimestamp(peerID: String, timestamp: Long) {
        try {
            val all = getAllLastReadTimestamps().toMutableMap()
            all[peerID.lowercase()] = timestamp

            val serializer = MapSerializer(String.serializer(), Long.serializer())
            val json = Json.encodeToString(serializer, all)
            settings[LAST_READ_TIMESTAMPS_KEY] = json
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun getAllLastReadTimestamps(): Map<String, Long> {
        return try {
            val json = settings.getStringOrNull(LAST_READ_TIMESTAMPS_KEY) ?: return emptyMap()
            val serializer = MapSerializer(String.serializer(), Long.serializer())
            Json.decodeFromString(serializer, json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "userPreferences"

        private const val USER_NAME = "userName"

        private const val USER_STATE_KEY = "userState"
        private const val ACTIVE_STATE_KEY = "activeState"
        private const val STATE_ID_KEY = "stateId"
        private const val PREVIOUS_CHANNEL_KEY = "previousChannel"

        private const val ACTIVE = "active"
        private const val BATTERY_OPTIMIZATION = "battery_optimization"

        private const val CHAT = "chat"
        private const val LOCATIONS = "locations"
        private const val SETTINGS = "settings"

        private const val LOCATION_NOTES = "locationNotes"

        private const val FAVORITES_KEY = "favorite_relationships"            // noiseHex -> relationship
        private const val PEERID_INDEX_KEY = "favorite_peerid_index"         // peerID(16-hex) -> npub
        private const val PEER_DISPLAY_NAME_KEY = "peer_display_names"
        private const val LAST_READ_TIMESTAMPS_KEY = "last_read_timestamps"  // peerID -> epoch millis
    }
}
