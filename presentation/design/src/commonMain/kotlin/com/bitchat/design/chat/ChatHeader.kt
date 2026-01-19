package com.bitchat.design.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.app_brand
import bitchatkmp.presentation.design.generated.resources.at_symbol
import bitchatkmp.presentation.design.generated.resources.back
import bitchatkmp.presentation.design.generated.resources.cd_add_favorite
import bitchatkmp.presentation.design.generated.resources.cd_connected_peers
import bitchatkmp.presentation.design.generated.resources.cd_encrypted
import bitchatkmp.presentation.design.generated.resources.cd_geohash_participants
import bitchatkmp.presentation.design.generated.resources.cd_handshake_failed
import bitchatkmp.presentation.design.generated.resources.cd_handshake_in_progress
import bitchatkmp.presentation.design.generated.resources.cd_nostr_reachable
import bitchatkmp.presentation.design.generated.resources.cd_ready_for_handshake
import bitchatkmp.presentation.design.generated.resources.cd_remove_favorite
import bitchatkmp.presentation.design.generated.resources.cd_teleported
import bitchatkmp.presentation.design.generated.resources.cd_toggle_bookmark
import bitchatkmp.presentation.design.generated.resources.cd_unread_private_messages
import bitchatkmp.presentation.design.generated.resources.channel_count_prefix
import bitchatkmp.presentation.design.generated.resources.chat_back
import bitchatkmp.presentation.design.generated.resources.chat_channel_prefix
import bitchatkmp.presentation.design.generated.resources.chat_leave
import com.bitchat.design.core.PoWIndicatorStyle
import com.bitchat.design.core.PoWStatusIndicator
import com.bitchat.design.location.LocationNotesButton
import com.bitchat.design.util.DebouncedSaver
import com.bitchat.design.util.singleOrTripleClickable
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.location.model.formatTitle
import com.bitchat.domain.user.model.FavoriteRelationship
import org.jetbrains.compose.resources.stringResource

@Composable
fun isFavoriteReactive(
    peerID: String,
    peerFingerprints: Map<String, String>,
    favoritePeers: Set<String>,
    favoriteRelationships: Map<String, FavoriteRelationship>
): Boolean {
    return remember(peerID, peerFingerprints, favoritePeers) {
        computeFavoriteStatus(peerID, peerFingerprints, favoritePeers, favoriteRelationships)
    }
}

internal fun computeFavoriteStatus(
    peerID: String,
    peerFingerprints: Map<String, String>,
    favoritePeers: Set<String>,
    favoriteRelationships: Map<String, FavoriteRelationship>
): Boolean {
    val lookupKey = peerID.lowercase()
    favoriteRelationships[lookupKey]?.let { return it.isFavorite }

    val fingerprint = peerFingerprints[peerID]
    return when {
        fingerprint != null -> favoritePeers.contains(fingerprint.lowercase())
        else -> favoritePeers.contains(lookupKey)
    }
}

@Composable
fun TorStatusDot(
    torRunning: Boolean,
    torBootstrapPercent: Int,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f

    val statusColor = when {
        torRunning && torBootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
        torRunning -> Color(0xFFFF9500)
        else -> Color(0xFFFF3B30)
    }

    Surface(
        color = statusColor,
        shape = CircleShape,
        modifier = modifier.size(8.dp)
    ) {}
}

@Composable
fun NoiseSessionIcon(
    sessionState: String?,
    modifier: Modifier = Modifier
) {
    val (icon, color, contentDescription) = when (sessionState) {
        "uninitialized" -> Triple(
            Icons.Outlined.NoEncryption,
            Color(0x87878700),
            stringResource(Res.string.cd_ready_for_handshake)
        )

        "handshaking" -> Triple(
            Icons.Outlined.Sync,
            Color(0x87878700),
            stringResource(Res.string.cd_handshake_in_progress)
        )

        "established" -> Triple(
            Icons.Filled.Lock,
            Color(0xFFFF9500),
            stringResource(Res.string.cd_encrypted)
        )

        else -> {
            Triple(
                Icons.Outlined.Warning,
                Color(0xFFFF4444),
                stringResource(Res.string.cd_handshake_failed)
            )
        }
    }

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier,
        tint = color
    )
}

@Composable
fun NicknameEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val debouncedSaver = remember {
        DebouncedSaver(
            scope = coroutineScope,
            debounceMillis = 500,
            onSave = onValueChange
        )
    }

    var localValue by remember { mutableStateOf(value) }
    var hadFocus by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        localValue = value
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Text(
            text = stringResource(Res.string.at_symbol),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.primary.copy(alpha = 0.8f)
        )

        BasicTextField(
            value = localValue,
            onValueChange = { newValue ->
                localValue = newValue
                debouncedSaver.submit(newValue)
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = colorScheme.primary,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    debouncedSaver.flush(localValue)
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .widthIn(max = 120.dp)
                .horizontalScroll(scrollState)
                .onFocusChanged { state ->
                    if (hadFocus && !state.isFocused) {
                        debouncedSaver.flush(localValue)
                    }
                    hadFocus = state.isFocused
                }
        )
    }
}

@Composable
fun PeerCounter(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    hasUnreadChannels: Map<String, Int>,
    isConnected: Boolean,
    selectedLocationChannel: Channel?,
    geohashPeople: List<GeoPerson>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val (peopleCount, countColor) = when (selectedLocationChannel) {
        is Channel.Location -> {
            val count = geohashPeople.size
            val green = Color(0xFF00C851)
            Pair(count, if (count > 0) green else Color.Gray)
        }

        is Channel.Mesh,
        null -> {
            val count = connectedPeers.size
            val meshBlue = Color(0xFF007AFF)
            Pair(count, if (isConnected && count > 0) meshBlue else Color.Gray)
        }

        is Channel.NostrDM,
        is Channel.MeshDM,
        is Channel.NamedChannel -> {
            Pair(0, Color.Gray)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onClick() }
    ) {
        Icon(
            imageVector = Icons.Default.Group,
            contentDescription = when (selectedLocationChannel) {
                is Channel.Location -> stringResource(Res.string.cd_geohash_participants)
                else -> stringResource(Res.string.cd_connected_peers)
            },
            modifier = Modifier.size(16.dp),
            tint = countColor
        )
        Spacer(modifier = Modifier.width(4.dp))

        Text(
            text = "$peopleCount",
            style = MaterialTheme.typography.bodyMedium,
            color = countColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        if (joinedChannels.isNotEmpty()) {
            val joinedText = stringResource(Res.string.channel_count_prefix) + "${joinedChannels.size}"
            println(joinedText)
            Text(
                text = joinedText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isConnected) Color(0xFF00C851) else Color.Red,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun ChatHeaderContent(
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
    permissionState: PermissionState,
    locationServicesEnabled: Boolean,
    hasNotes: Boolean,
    powEnabled: Boolean,
    powDifficulty: Int,
    isMining: Boolean,
    torEnabled: Boolean = false,
    torRunning: Boolean = false,
    torBootstrapPercent: Int = 0,
    onNicknameChange: (String) -> Unit,
    onToggleFavoritePeer: (String) -> Unit,
    setNickname: (String) -> Unit,
    onLeaveChannel: (String) -> Unit,
    onBackClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onTripleClick: () -> Unit,
    onShowAppInfo: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    openLatestUnreadPrivateChat: () -> Unit,
    connectedPeers: List<String> = emptyList(),
    joinedChannels: Set<String> = emptySet(),
    hasUnreadChannels: Map<String, Int> = emptyMap(),
    hasUnreadPrivateMessages: Boolean = false,
    isConnected: Boolean = false,
    isCurrentChannelBookmarked: Boolean = false,
    onToggleBookmark: (String) -> Unit = {},
) {
    when {
        selectedPrivatePeer != null -> {
            val isFavorite = isFavoriteReactive(
                peerID = selectedPrivatePeer,
                peerFingerprints = peerFingerprints,
                favoritePeers = favoritePeers,
                favoriteRelationships = favoriteRelationships
            )
            val sessionState = peerSessionStates[selectedPrivatePeer]

            val relationship = favoriteRelationships[selectedPrivatePeer.lowercase()]
            val isMutual = relationship?.isMutual ?: false

            PrivateChatHeader(
                channel = selectedChannel ?: selectedLocationChannel,
                isFavorite = isFavorite,
                isMutual = isMutual,
                sessionState = sessionState,
                geohashPeople = geohashPeople,
                onBackClick = onBackClick,
                onToggleFavorite = {
                    val peerID = when (selectedLocationChannel) {
                        is Channel.MeshDM -> selectedLocationChannel.peerID
                        is Channel.NostrDM -> selectedLocationChannel.peerID
                        else -> selectedPrivatePeer
                    }
                    onToggleFavoritePeer(peerID)
                },
            )
        }

        currentChannel != null -> {
            ChannelHeader(
                channel = currentChannel,
                onBackClick = onBackClick,
                onLeaveChannel = {
                    onLeaveChannel(currentChannel)
                },
                onSidebarClick = onSidebarClick
            )
        }

        else -> {
            MainHeader(
                nickname = nickname,
                onNicknameChange = setNickname,
                selectedLocationChannel = selectedLocationChannel,
                permissionState = permissionState,
                locationServicesEnabled = locationServicesEnabled,
                hasNotes = hasNotes,
                powEnabled = powEnabled,
                powDifficulty = powDifficulty,
                isMining = isMining,
                torEnabled = torEnabled,
                torRunning = torRunning,
                torBootstrapPercent = torBootstrapPercent,
                teleported = teleported,
                onTitleClick = onShowAppInfo,
                onTripleTitleClick = onTripleClick,
                onSidebarClick = onSidebarClick,
                onLocationChannelsClick = onLocationChannelsClick,
                onLocationNotesClick = onLocationNotesClick,
                openLatestUnreadPrivateChat = openLatestUnreadPrivateChat,
                connectedPeers = connectedPeers,
                joinedChannels = joinedChannels,
                hasUnreadChannels = hasUnreadChannels,
                hasUnreadPrivateMessages = hasUnreadPrivateMessages,
                isConnected = isConnected,
                geohashPeople = geohashPeople,
                isCurrentChannelBookmarked = isCurrentChannelBookmarked,
                onToggleBookmark = onToggleBookmark,
            )
        }
    }
}

@Composable
private fun PrivateChatHeader(
    channel: Channel,
    isFavorite: Boolean,
    isMutual: Boolean,
    sessionState: String?,
    geohashPeople: List<GeoPerson>,
    onBackClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    val titleText: String = when (channel) {
        is Channel.NostrDM -> {
            val participants = remember(channel.sourceGeohash, geohashPeople) {
                if (channel.sourceGeohash != null) {
                    geohashPeople
                        .filter { it.id != channel.fullPubkey }
                        .associate { it.id to it.displayName }
                } else {
                    emptyMap()
                }
            }

            channel.formatTitle(participants)
        }

        is Channel.MeshDM -> channel.formatTitle()
        else -> "Unknown"
    }

    val isNostrDM = channel is Channel.NostrDM

    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onBackClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = colorScheme.primary
            ),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-8).dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.back),
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(Res.string.chat_back),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.Center)
        ) {

            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFFF9500) // Orange
            )

            Spacer(modifier = Modifier.width(4.dp))

            val showGlobe = isNostrDM || (sessionState != "established" && isMutual)
            if (showGlobe) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = stringResource(Res.string.cd_nostr_reachable),
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF9B59B6)
                )
            } else {
                NoiseSessionIcon(
                    sessionState = sessionState,
                    modifier = Modifier.size(14.dp)
                )
            }

        }

        IconButton(
            onClick = {
                onToggleFavorite()
            },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = if (isFavorite)
                    stringResource(Res.string.cd_remove_favorite)
                else stringResource(Res.string.cd_add_favorite),
                modifier = Modifier.size(18.dp),
                tint = if (isFavorite) Color(0xFFFFD700) else Color(0x87878700)
            )
        }
    }
}

@Composable
private fun ChannelHeader(
    channel: String,
    onBackClick: () -> Unit,
    onLeaveChannel: () -> Unit,
    onSidebarClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onBackClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = colorScheme.primary
            ),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-8).dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.back),
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(Res.string.chat_back),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.primary
                )
            }
        }

        Text(
            text = stringResource(Res.string.chat_channel_prefix, channel),
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFFF9500),
            modifier = Modifier
                .align(Alignment.Center)
                .clickable { onSidebarClick() }
        )

        TextButton(
            onClick = onLeaveChannel,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Text(
                text = stringResource(Res.string.chat_leave),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Red
            )
        }
    }
}

@Composable
private fun MainHeader(
    nickname: String,
    selectedLocationChannel: Channel,
    teleported: Boolean,
    permissionState: PermissionState,
    locationServicesEnabled: Boolean,
    hasNotes: Boolean,
    powEnabled: Boolean,
    powDifficulty: Int,
    isMining: Boolean,
    torEnabled: Boolean = false,
    torRunning: Boolean = false,
    torBootstrapPercent: Int = 0,
    connectedPeers: List<String> = emptyList(),
    joinedChannels: Set<String> = emptySet(),
    hasUnreadChannels: Map<String, Int> = emptyMap(),
    hasUnreadPrivateMessages: Boolean = false,
    isConnected: Boolean = false,
    geohashPeople: List<GeoPerson> = emptyList(),
    isCurrentChannelBookmarked: Boolean = false,
    onToggleBookmark: (String) -> Unit = {},
    onNicknameChange: (String) -> Unit,
    onTitleClick: () -> Unit,
    onTripleTitleClick: () -> Unit,
    onSidebarClick: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    openLatestUnreadPrivateChat: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = stringResource(Res.string.app_brand),
                style = MaterialTheme.typography.headlineSmall,
                color = colorScheme.primary,
                modifier = Modifier.singleOrTripleClickable(
                    onSingleClick = onTitleClick,
                    onTripleClick = onTripleTitleClick
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.width(2.dp))

            NicknameEditor(
                value = nickname,
                onValueChange = onNicknameChange
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (hasUnreadPrivateMessages) {
                Icon(
                    imageVector = Icons.Filled.Email,
                    contentDescription = stringResource(Res.string.cd_unread_private_messages),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { openLatestUnreadPrivateChat() },
                    tint = Color(0xFFFF9500)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                LocationChannelsButton(
                    selectedChannel = selectedLocationChannel,
                    teleported = teleported,
                    onClick = onLocationChannelsClick
                )

                val currentGeohash: String? = when (val sc = selectedLocationChannel) {
                    is Channel.Location -> sc.geohash
                    else -> null
                }
                if (currentGeohash != null) {
                    Box(
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .size(20.dp)
                            .clickable { onToggleBookmark(currentGeohash) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isCurrentChannelBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = stringResource(Res.string.cd_toggle_bookmark),
                            tint = if (isCurrentChannelBookmarked) Color(0xFF00C851) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            LocationNotesButton(
                permissionState = permissionState,
                selectedLocationChannel = selectedLocationChannel,
                locationServicesEnabled = locationServicesEnabled,
                hasNotes = hasNotes,
                onClick = onLocationNotesClick
            )

            if (torEnabled) {
                TorStatusDot(
                    torRunning = torRunning,
                    torBootstrapPercent = torBootstrapPercent,
                    modifier = Modifier
                        .size(8.dp)
                        .padding(start = 0.dp, end = 2.dp)
                )
            }

            PoWStatusIndicator(
                powEnabled = powEnabled,
                powDifficulty = powDifficulty,
                isMining = isMining,
                modifier = Modifier,
                style = PoWIndicatorStyle.COMPACT
            )
            Spacer(modifier = Modifier.width(2.dp))
            PeerCounter(
                connectedPeers = connectedPeers, // TODO: Filter out own peer ID when available
                joinedChannels = joinedChannels,
                hasUnreadChannels = hasUnreadChannels,
                isConnected = isConnected,
                selectedLocationChannel = selectedLocationChannel,
                geohashPeople = geohashPeople,
                onClick = onSidebarClick
            )
        }
    }
}

@Composable
private fun LocationChannelsButton(
    selectedChannel: Channel,
    teleported: Boolean,
    onClick: () -> Unit
) {
    val (badgeText, badgeColor) = when (selectedChannel) {
        is Channel.Mesh -> {
            "#mesh" to Color(0xFF007AFF)
        }

        is Channel.Location -> {
            val geohash = selectedChannel.geohash
            "#$geohash" to Color(0xFF00C851)
        }

        is Channel.NostrDM,
        is Channel.MeshDM -> {
            "DM" to Color(0xFFFF9500)
        }

        is Channel.NamedChannel -> {
            "#${selectedChannel.channelName}" to Color(0xFF5856D6)
        }
    }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = badgeColor
        ),
        contentPadding = PaddingValues(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = badgeColor,
                maxLines = 1
            )

            if (teleported) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.PinDrop,
                    contentDescription = stringResource(Res.string.cd_teleported),
                    modifier = Modifier.size(12.dp),
                    tint = badgeColor
                )
            }
        }
    }
}
