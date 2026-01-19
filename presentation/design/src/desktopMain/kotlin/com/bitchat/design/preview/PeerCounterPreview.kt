package com.bitchat.design.preview

import androidx.compose.runtime.Composable
import com.bitchat.design.chat.PeerCounter
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun PeerCounterPreview() {
    PeerCounter(
        connectedPeers = listOf(),
        joinedChannels = setOf("#Bitcoin", "#USA"),
        hasUnreadChannels = mapOf(),
        isConnected = true,
        selectedLocationChannel = null,
        geohashPeople = listOf(),
        onClick = {},
    )
}
