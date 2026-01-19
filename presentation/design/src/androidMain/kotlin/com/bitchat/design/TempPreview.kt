package com.bitchat.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.bitchat.design.chat.PeerCounter

@Preview
@Composable
fun Preview_WelcomeContent() {
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
