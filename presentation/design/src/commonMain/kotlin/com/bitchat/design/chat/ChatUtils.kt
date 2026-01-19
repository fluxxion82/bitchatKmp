package com.bitchat.design.chat

import androidx.compose.ui.graphics.Color

fun splitSuffix(displayName: String): Pair<String, String> {
    val hashIndex = displayName.indexOf('#')
    return if (hashIndex != -1) {
        displayName.substring(0, hashIndex) to displayName.substring(hashIndex)
    } else {
        displayName to ""
    }
}

fun truncateNickname(nickname: String): String {
    return if (nickname.length > 16) {
        nickname.substring(0, 13) + "..."
    } else {
        nickname
    }
}

fun colorForPeer(peerID: String, isDark: Boolean): Color {
    val hash = peerID.hashCode()

    return if (isDark) {
        when (hash % 8) {
            0 -> Color(0xFF00C851)
            1 -> Color(0xFF007AFF)
            2 -> Color(0xFFFF9500)
            3 -> Color(0xFF9C27B0)
            4 -> Color(0xFFFF4444)
            5 -> Color(0xFF00BCD4)
            6 -> Color(0xFFFFEB3B)
            else -> Color(0xFFFF5722)
        }
    } else {
        when (hash % 8) {
            0 -> Color(0xFF008000)
            1 -> Color(0xFF0056B3)
            2 -> Color(0xFFCC7A00)
            3 -> Color(0xFF7B1FA2)
            4 -> Color(0xFFCC0000)
            5 -> Color(0xFF00838F)
            6 -> Color(0xFFC8B900)
            else -> Color(0xFFD84315)
        }
    }
}
