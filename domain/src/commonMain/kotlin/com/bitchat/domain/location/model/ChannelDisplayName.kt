package com.bitchat.domain.location.model

fun generateGeohashDisplayName(
    baseNickname: String,
    pubkeySuffix: String,
    participants: Map<String, String>,
    currentPubkey: String,
    originalDisplayName: String? = null
): String {
    // Extract the base nickname (everything before # if present)
    // Input: "anon6458#9a2b" → base = "anon6458"
    // Input: "anon6458" → base = "anon6458"
    val base = baseNickname.substringBefore('#')

    // Check for collisions: another participant with same base nickname
    val hasCollision = participants.any { (pubkey, name) ->
        pubkey != currentPubkey && name.substringBefore('#') == base
    }

    return if (hasCollision) {
        "$base#$pubkeySuffix"  // "anon6458#abcd"
    } else {
        base  // "anon6458"
    }
}

fun Channel.NostrDM.formatTitle(participants: Map<String, String> = emptyMap()): String {
    val geohashPart = sourceGeohash ?: "direct"

    val displayNamePart = displayName?.let { name ->
        generateGeohashDisplayName(
            baseNickname = name,
            pubkeySuffix = fullPubkey.takeLast(4),
            participants = participants,
            currentPubkey = fullPubkey,
            originalDisplayName = name
        )
    } ?: fullPubkey.take(12)

    return "#$geohashPart/@$displayNamePart"
}

fun Channel.MeshDM.formatTitle(): String {
    return displayName?.let { "@$it" } ?: peerID.take(12)
}
