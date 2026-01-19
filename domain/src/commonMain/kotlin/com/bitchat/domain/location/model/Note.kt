package com.bitchat.domain.location.model

data class Note(
    val id: String,
    val pubkey: String,
    val content: String,
    val createdAt: Int,
    val nickname: String?
) {
    val displayName: String
        get() {
            val suffix = pubkey.takeLast(4)
            val nick = nickname?.trim()
            return if (!nick.isNullOrEmpty()) {
                "$nick#$suffix"
            } else {
                "anon#$suffix"
            }
        }
}
