package com.bitchat.domain.user.model

import com.bitchat.domain.location.model.Channel

sealed interface UserStateAction {
    data object GrantPermissions : UserStateAction
    data object HandledOptimizations : UserStateAction
    data object Locations : UserStateAction
    data object Settings : UserStateAction
    data object LocationNotes : UserStateAction
    data class Chat(val channel: Channel, val isTeleport: Boolean = false) : UserStateAction
    data class MeshDM(
        val peerID: String,
        val displayName: String?,
    ) : UserStateAction

    data class NostrDM(
        val peerID: String? = null,
        val fullPubkey: String? = null,
        val sourceGeohash: String? = null,
        val displayName: String? = null,
    ) : UserStateAction
}
