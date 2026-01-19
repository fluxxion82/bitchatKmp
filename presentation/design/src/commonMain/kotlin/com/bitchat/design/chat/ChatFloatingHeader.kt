package com.bitchat.design.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.user.model.FavoriteRelationship

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatFloatingHeader(
    headerHeight: Dp,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    favoritePeers: Set<String>,
    favoriteRelationships: Map<String, FavoriteRelationship>,
    peerFingerprints: Map<String, String>,
    peerSessionStates: Map<String, String>,
    geohashPeople: List<GeoPerson>,
    peerNicknames: Map<String, String>,
    selectedLocationChannel: Channel,
    selectedChannel: Channel? = null,
    teleported: Boolean,
    permissionState: PermissionState = PermissionState.AUTHORIZED,
    locationServicesEnabled: Boolean = true,
    hasNotes: Boolean = false,
    powEnabled: Boolean = false,
    powDifficulty: Int = 0,
    isMining: Boolean = false,
    torEnabled: Boolean = false,
    torRunning: Boolean = false,
    torBootstrapPercent: Int = 0,
    setNickname: (String) -> Unit,
    onNicknameChange: (String) -> Unit = {},
    onToggleFavoritePeer: (String) -> Unit,
    onLeaveChannel: (String) -> Unit,
    onSidebarToggle: () -> Unit,
    onShowAppInfo: () -> Unit,
    onPanicClear: () -> Unit,
    endPrivateChat: () -> Unit,
    switchToChannel: (String?) -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    openLatestUnreadPrivateChat: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars),
        color = MaterialTheme.colorScheme.background
    ) {
        TopAppBar(
            title = {
                ChatHeaderContent(
                    selectedPrivatePeer = selectedPrivatePeer,
                    currentChannel = currentChannel,
                    nickname = nickname,
                    favoritePeers = favoritePeers,
                    favoriteRelationships = favoriteRelationships,
                    peerFingerprints = peerFingerprints,
                    peerSessionStates = peerSessionStates,
                    geohashPeople = geohashPeople,
                    selectedLocationChannel = selectedLocationChannel,
                    selectedChannel = selectedChannel,
                    peerNicknames = peerNicknames,
                    teleported = teleported,
                    permissionState = permissionState,
                    locationServicesEnabled = locationServicesEnabled,
                    hasNotes = hasNotes,
                    powEnabled = powEnabled,
                    powDifficulty = powDifficulty,
                    isMining = isMining,
                    torEnabled = torEnabled,
                    torRunning = torRunning,
                    torBootstrapPercent = torBootstrapPercent,
                    setNickname = setNickname,
                    onNicknameChange = onNicknameChange,
                    onLeaveChannel = onLeaveChannel,
                    onToggleFavoritePeer = onToggleFavoritePeer,
                    onBackClick = {
                        when {
                            selectedPrivatePeer != null -> endPrivateChat()
                            currentChannel != null -> switchToChannel(null)
                        }
                    },
                    onSidebarClick = onSidebarToggle,
                    onTripleClick = onPanicClear,
                    onShowAppInfo = onShowAppInfo,
                    onLocationChannelsClick = onLocationChannelsClick,
                    onLocationNotesClick = {
                        onLocationNotesClick()
                    },
                    openLatestUnreadPrivateChat = openLatestUnreadPrivateChat,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.height(headerHeight)
        )
    }
}
