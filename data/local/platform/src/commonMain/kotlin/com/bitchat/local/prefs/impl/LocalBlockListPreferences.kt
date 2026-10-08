package com.bitchat.local.prefs.impl

import com.bitchat.domain.user.model.BlockedUser
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.readForUpdate
import com.bitchat.local.prefs.writeUpdate
import com.russhwolf.settings.set
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class LocalBlockListPreferences(
    encryptedPreferenceFactory: EncryptionSettingsFactory,
) : BlockListPreferences {
    private val settings = encryptedPreferenceFactory.createEncrypted(PREFERENCES_NAME)

    override fun getMeshBlockedUsers(): Map<String, BlockedUser> {
        return try {
            decodeBlockedUsers(settings.getStringOrNull(MESH_BLOCKED_KEY))
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun decodeBlockedUsers(json: String?): Map<String, BlockedUser> {
        return try {
            if (json == null) return emptyMap()
            val serializer = MapSerializer(String.serializer(), BlockedUser.serializer())
            Json.decodeFromString(serializer, json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun isMeshUserBlocked(fingerprint: String): Boolean {
        return getMeshBlockedUsers().containsKey(fingerprint.lowercase())
    }

    override fun addMeshBlockedUser(blockedUser: BlockedUser) {
        try {
            // Not getMeshBlockedUsers(): that answers "nobody" for a store that did not answer, and the
            // list written from it would be all that is left of the block list.
            val saved = settings.readForUpdate(MESH_BLOCKED_KEY)
            val all = decodeBlockedUsers(saved.text).toMutableMap()
            all[blockedUser.identifier.lowercase()] = blockedUser

            val serializer = MapSerializer(String.serializer(), BlockedUser.serializer())
            val json = Json.encodeToString(serializer, all)
            settings.writeUpdate(MESH_BLOCKED_KEY, saved, json)
        } catch (e: Exception) {
            // Log error
        }
    }

    override fun removeMeshBlockedUser(fingerprint: String) {
        try {
            // Not getMeshBlockedUsers(): that answers "nobody" for a store that did not answer, and the
            // list written from it would be all that is left of the block list.
            val saved = settings.readForUpdate(MESH_BLOCKED_KEY)
            val all = decodeBlockedUsers(saved.text).toMutableMap()
            all.remove(fingerprint.lowercase())

            val serializer = MapSerializer(String.serializer(), BlockedUser.serializer())
            val json = Json.encodeToString(serializer, all)
            settings.writeUpdate(MESH_BLOCKED_KEY, saved, json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getGeohashBlockedUsers(): Map<String, BlockedUser> {
        return try {
            decodeBlockedUsers(settings.getStringOrNull(GEOHASH_BLOCKED_KEY))
        } catch (e: Exception) {
            emptyMap()
        }
    }

    override fun isGeohashUserBlocked(pubkeyHex: String): Boolean {
        return getGeohashBlockedUsers().containsKey(pubkeyHex.lowercase())
    }

    override fun addGeohashBlockedUser(blockedUser: BlockedUser) {
        try {
            // Not getGeohashBlockedUsers(): that answers "nobody" for a store that did not answer, and the
            // list written from it would be all that is left of the block list.
            val saved = settings.readForUpdate(GEOHASH_BLOCKED_KEY)
            val all = decodeBlockedUsers(saved.text).toMutableMap()
            all[blockedUser.identifier.lowercase()] = blockedUser

            val serializer = MapSerializer(String.serializer(), BlockedUser.serializer())
            val json = Json.encodeToString(serializer, all)
            settings.writeUpdate(GEOHASH_BLOCKED_KEY, saved, json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun removeGeohashBlockedUser(pubkeyHex: String) {
        try {
            // Not getGeohashBlockedUsers(): that answers "nobody" for a store that did not answer, and the
            // list written from it would be all that is left of the block list.
            val saved = settings.readForUpdate(GEOHASH_BLOCKED_KEY)
            val all = decodeBlockedUsers(saved.text).toMutableMap()
            all.remove(pubkeyHex.lowercase())

            val serializer = MapSerializer(String.serializer(), BlockedUser.serializer())
            val json = Json.encodeToString(serializer, all)
            settings.writeUpdate(GEOHASH_BLOCKED_KEY, saved, json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getAllBlockedUsers(): List<BlockedUser> {
        val meshBlocked = getMeshBlockedUsers().values
        val geohashBlocked = getGeohashBlockedUsers().values
        return (meshBlocked + geohashBlocked).toList()
    }

    override fun clearAllBlocks() {
        settings[MESH_BLOCKED_KEY] = null
        settings[GEOHASH_BLOCKED_KEY] = null
    }

    companion object {
        private const val PREFERENCES_NAME = "block_list_prefs"
        private const val MESH_BLOCKED_KEY = "mesh_blocked_users"
        private const val GEOHASH_BLOCKED_KEY = "geohash_blocked_users"
    }
}
