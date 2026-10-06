package com.bitchat.nostr.util

const val MAX_NICKNAME_CHARS = com.bitchat.domain.user.MAX_NICKNAME_CHARS

fun sanitizedNickname(raw: String?): String? = com.bitchat.domain.user.sanitizedNickname(raw)
