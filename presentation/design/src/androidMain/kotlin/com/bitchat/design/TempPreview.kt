package com.bitchat.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.bitchat.design.chat.PeerCounter

@Preview
@Composable
fun Preview_WelcomeContent() {
    PeerCounter(
        connectedPeers = listOf("269e37bb6be7caf9", "9343bbdb113d0118"),
        joinedChannels = setOf("#Bitcoin", "#USA"),
        hasUnreadChannels = mapOf(),
        selectedLocationChannel = null,
        geohashPeople = listOf(),
        onClick = {},
    )
}
