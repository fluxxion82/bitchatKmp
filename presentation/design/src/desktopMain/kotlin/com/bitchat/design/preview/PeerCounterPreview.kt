package com.bitchat.design.preview

import androidx.compose.runtime.Composable
import com.bitchat.design.chat.PeerCounter
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun PeerCounterPreview() {
    PeerCounter(
        connectedPeers = listOf("269e37bb6be7caf9", "9343bbdb113d0118"),
        joinedChannels = setOf("#Bitcoin", "#USA"),
        hasUnreadChannels = mapOf(),
        selectedLocationChannel = null,
        geohashPeople = listOf(),
        onClick = {},
    )
}
