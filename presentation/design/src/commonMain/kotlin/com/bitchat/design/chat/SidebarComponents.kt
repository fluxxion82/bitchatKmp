package com.bitchat.design.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_add_favorite
import bitchatkmp.presentation.design.generated.resources.cd_direct_bluetooth
import bitchatkmp.presentation.design.generated.resources.cd_leave_channel
import bitchatkmp.presentation.design.generated.resources.cd_offline_favorite
import bitchatkmp.presentation.design.generated.resources.cd_reachable_via_nostr
import bitchatkmp.presentation.design.generated.resources.cd_remove_favorite
import bitchatkmp.presentation.design.generated.resources.cd_routed
import bitchatkmp.presentation.design.generated.resources.cd_unread_message
import bitchatkmp.presentation.design.generated.resources.channels
import bitchatkmp.presentation.design.generated.resources.no_one_connected
import bitchatkmp.presentation.design.generated.resources.offline_favorites
import bitchatkmp.presentation.design.generated.resources.people
import bitchatkmp.presentation.design.generated.resources.your_network
import com.bitchat.design.BASE_FONT_SIZE
import com.bitchat.domain.chat.model.ChannelInfo
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.user.model.FavoriteRelationship
import org.jetbrains.compose.resources.stringResource

@Composable
fun SidebarOverlay(
    connectedPeers: List<String>,
    joinedChannels: Set<String>,
    currentChannel: String?,
    selectedPrivatePeer: String?,

    peerNicknames: Map<String, String>,
    peerDirect: Map<String, Boolean>,
    peerSessionStates: Map<String, String> = emptyMap(),
    favoritePeers: Set<String>,
    favoriteRelationships: Map<String, FavoriteRelationship> = emptyMap(),

    hasUnreadPrivateMessages: Set<String>,
    unreadChannelMessages: Map<String, Int>,
    privateChats: Map<String, Int>,

    nickname: String,
    selectedLocationChannel: Channel = Channel.Mesh,
    geohashPeople: List<GeoPerson> = emptyList(),
    geohashSelfId: String? = null,
    isTeleported: Boolean = false,
    teleportedPeople: Set<String> = emptySet(),

    joinedNamedChannels: List<ChannelInfo> = emptyList(),

    onChannelClick: (String) -> Unit,
    onLeaveChannel: (String) -> Unit,
    onNamedChannelClick: (Channel.NamedChannel) -> Unit = {},
    onLeaveNamedChannel: (String) -> Unit = {},
    onGeohashPersonTap: (GeoPerson, String?) -> Unit,
    onMeshPersonTap: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,

    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(indication = null, interactionSource = interactionSource) { onDismiss() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .align(Alignment.CenterEnd)
                .clickable { /* Prevent dismissing when clicking sidebar */ }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Color.Gray.copy(alpha = 0.3f))
            )

            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .background(colorScheme.background.copy(alpha = 0.95f))
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                SidebarHeader()

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (joinedChannels.isNotEmpty()) {
                        item {
                            ChannelsSection(
                                channels = joinedChannels.toList(),
                                currentChannel = currentChannel,
                                onChannelClick = onChannelClick,
                                onLeaveChannel = onLeaveChannel,
                                unreadChannelMessages = unreadChannelMessages
                            )
                        }

                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    if (joinedNamedChannels.isNotEmpty()) {
                        item {
                            NamedChannelsSection(
                                channels = joinedNamedChannels,
                                currentChannel = selectedLocationChannel,
                                onChannelClick = onNamedChannelClick,
                                onLeaveChannel = onLeaveNamedChannel
                            )
                        }

                        item {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }

                    item {
                        val visibleConnectedPeers = connectedPeers.filterNot { peerSessionStates[it] == "disconnected" }

                        when (selectedLocationChannel) {
                            is Channel.Location -> {
                                GeohashPeopleList(
                                    people = geohashPeople,
                                    myPersonId = geohashSelfId,
                                    myNickname = nickname,
                                    hasUnreadPrivateMessages = hasUnreadPrivateMessages,
                                    isSelfTeleported = isTeleported,
                                    teleportedPeople = teleportedPeople,
                                    onPersonTap = { person ->
                                        val currentGeohash = selectedLocationChannel.geohash
                                        onGeohashPersonTap(person, currentGeohash)
                                        onDismiss()
                                    }
                                )
                            }

                            else -> {
                                PeopleSection(
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    connectedPeers = visibleConnectedPeers,
                                    peerNicknames = peerNicknames,
                                    peerDirect = peerDirect,
                                    nickname = nickname,
                                    selectedPrivatePeer = selectedPrivatePeer,
                                    favoritePeers = favoritePeers,
                                    hasUnreadPrivateMessages = hasUnreadPrivateMessages,
                                    privateChats = privateChats,
                                    onMeshPersonTap = onMeshPersonTap,
                                    onToggleFavorite = onToggleFavorite,
                                    onDismiss = onDismiss
                                )
                            }
                        }
                    }

                    if (selectedLocationChannel !is Channel.Location) {
                        val offlineFavorites = computeOfflineFavorites(favoriteRelationships, connectedPeers)

                        if (offlineFavorites.isNotEmpty()) {
                            item {
                                OfflineFavoritesSection(
                                    offlineFavorites = offlineFavorites,
                                    selectedPrivatePeer = selectedPrivatePeer,
                                    onToggleFavorite = onToggleFavorite,
                                    onDismiss = onDismiss
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarHeader() {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .height(42.dp)
            .fillMaxWidth()
            .background(colorScheme.background.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(Res.string.your_network).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            color = colorScheme.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun ChannelsSection(
    channels: List<String>,
    currentChannel: String?,
    onChannelClick: (String) -> Unit,
    onLeaveChannel: (String) -> Unit,
    unreadChannelMessages: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.channels).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }

        channels.forEach { channel ->
            val isSelected = channel == currentChannel
            val unreadCount = unreadChannelMessages[channel] ?: 0

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChannelClick(channel) }
                    .background(
                        if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UnreadBadge(
                    count = unreadCount,
                    modifier = Modifier.padding(end = 8.dp)
                )

                Text(
                    text = channel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { onLeaveChannel(channel) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(Res.string.cd_leave_channel),
                        modifier = Modifier.size(14.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun NamedChannelsSection(
    channels: List<ChannelInfo>,
    currentChannel: Channel?,
    onChannelClick: (Channel.NamedChannel) -> Unit,
    onLeaveChannel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "NAMED CHANNELS",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }

        channels.forEach { channel ->
            val isSelected = currentChannel is Channel.NamedChannel &&
                    currentChannel.channelName == channel.name

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChannelClick(Channel.NamedChannel(channel.name)) }
                    .background(
                        if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (channel.isProtected) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Protected",
                        modifier = Modifier.size(12.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "${channel.memberCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurface.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { onLeaveChannel(channel.name) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Leave channel",
                        modifier = Modifier.size(14.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun PeopleSection(
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerDirect: Map<String, Boolean>,
    nickname: String,
    selectedPrivatePeer: String?,
    favoritePeers: Set<String>,
    hasUnreadPrivateMessages: Set<String>,
    privateChats: Map<String, Int>,
    onMeshPersonTap: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.people).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }

        if (connectedPeers.isEmpty()) {
            Text(
                text = stringResource(Res.string.no_one_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        val sortedPeers = connectedPeers.sortedWith(
            compareBy<String> { !hasUnreadPrivateMessages.contains(it) }
                .thenBy { !favoritePeers.contains(it) }
                .thenBy { (if (it == nickname) "You" else (peerNicknames[it] ?: it)).lowercase() }
        )

        val baseNameCounts = mutableMapOf<String, Int>()
        sortedPeers.forEach { pid ->
            val displayName = if (pid == nickname) "You" else (peerNicknames[pid] ?: pid.take(12))
            val (baseName, _) = splitSuffix(displayName)
            if (baseName != "You") {
                baseNameCounts[baseName] = (baseNameCounts[baseName] ?: 0) + 1
            }
        }

        sortedPeers.forEach { peerID ->
            val isFavorite = favoritePeers.contains(peerID)
            val hasUnread = hasUnreadPrivateMessages.contains(peerID)
            val unreadCount = privateChats[peerID] ?: 0

            val displayName = if (peerID == nickname) "You" else (peerNicknames[peerID] ?: peerID.take(12))
            val (baseName, _) = splitSuffix(displayName)
            val showHashSuffix = (baseNameCounts[baseName] ?: 0) > 1

            val isDirect = peerDirect[peerID] ?: false

            PeerItem(
                peerID = peerID,
                displayName = displayName,
                isDirect = isDirect,
                isSelected = peerID == selectedPrivatePeer,
                isFavorite = isFavorite,
                hasUnreadDM = hasUnread,
                unreadCount = unreadCount,
                showHashSuffix = showHashSuffix,
                onItemClick = {
                    onMeshPersonTap(peerID)
                    onDismiss()
                },
                onToggleFavorite = { onToggleFavorite(peerID) }
            )
        }
    }
}

@Composable
private fun PeerItem(
    peerID: String,
    displayName: String,
    isDirect: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    hasUnreadDM: Boolean,
    unreadCount: Int,
    showHashSuffix: Boolean,
    showNostrGlobe: Boolean = false,
    isOfflineFavorite: Boolean = false,
    onItemClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val (baseNameRaw, suffixRaw) = splitSuffix(displayName)
    val baseName = truncateNickname(baseNameRaw)
    val suffix = if (showHashSuffix) suffixRaw else ""
    val isMe = displayName == "You"

    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val assignedColor = colorForPeer(peerID, isDark)
    val baseColor = if (isMe) Color(0xFFFF9500) else assignedColor

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
            .background(
                if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasUnreadDM) {
            Icon(
                imageVector = Icons.Filled.Email,
                contentDescription = stringResource(Res.string.cd_unread_message),
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFFF9500)
            )
        } else {
            when {
                showNostrGlobe -> {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = stringResource(Res.string.cd_reachable_via_nostr),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF9C27B0)
                    )
                }

                isOfflineFavorite -> {
                    Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = stringResource(Res.string.cd_offline_favorite),
                        modifier = Modifier.size(16.dp),
                        tint = Color.Gray
                    )
                }

                else -> {
                    Icon(
                        imageVector = if (isDirect) Icons.Outlined.SettingsInputAntenna else Icons.Filled.Route,
                        contentDescription = if (isDirect)
                            stringResource(Res.string.cd_direct_bluetooth)
                        else
                            stringResource(Res.string.cd_routed),
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = baseName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = BASE_FONT_SIZE.sp,
                    fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                ),
                color = baseColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (suffix.isNotEmpty()) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = baseColor.copy(alpha = 0.6f)
                )
            }
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = if (isFavorite)
                    stringResource(Res.string.cd_remove_favorite)
                else
                    stringResource(Res.string.cd_add_favorite),
                modifier = Modifier.size(16.dp),
                tint = if (isFavorite) Color(0xFFFFD700) else Color(0x87878700)
            )
        }
    }
}

@Composable
private fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Box(
            modifier = modifier
                .padding(horizontal = 2.dp, vertical = 0.dp)
                .background(
                    color = Color(0xFFFFD700),
                    shape = RoundedCornerShape(10.dp)
                )
                .defaultMinSize(minWidth = 14.dp, minHeight = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.Black
            )
        }
    }
}

@Composable
private fun OfflineFavoritesSection(
    offlineFavorites: List<FavoriteRelationship>,
    selectedPrivatePeer: String?,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.StarOutline,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.offline_favorites).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }

        offlineFavorites.forEach { relationship ->
            val peerID = relationship.peerNoisePublicKeyHex
            val displayName = relationship.peerNickname
            val isMutual = relationship.isMutual

            PeerItem(
                peerID = peerID,
                displayName = displayName,
                isDirect = false,
                isSelected = peerID == selectedPrivatePeer,
                isFavorite = true,
                hasUnreadDM = false,
                unreadCount = 0,
                showHashSuffix = false,
                showNostrGlobe = isMutual,
                isOfflineFavorite = true,
                onItemClick = {
                    // Offline favorites can't be messaged directly via mesh
                    // Could potentially open Nostr DM if mutual, but for now just dismiss
                    onDismiss()
                },
                onToggleFavorite = { onToggleFavorite(peerID) }
            )
        }
    }
}

internal fun computeOfflineFavorites(
    favoriteRelationships: Map<String, FavoriteRelationship>,
    connectedPeers: List<String>
): List<FavoriteRelationship> {
    val connectedPeersLower = connectedPeers.map { it.lowercase() }.toSet()

    return favoriteRelationships.values
        .filter { rel ->
            if (!rel.isFavorite) return@filter false
            // Normalize: strip "nostr_" prefix if present to match mesh peer IDs
            val normalizedKey = rel.peerNoisePublicKeyHex.lowercase().removePrefix("nostr_")
            !connectedPeersLower.contains(normalizedKey)
        }
        .distinctBy { rel ->
            // For deduplication, prefer Nostr pubkey if available, otherwise use normalized noise key
            rel.peerNostrPublicKey ?: rel.peerNoisePublicKeyHex.lowercase().removePrefix("nostr_")
        }
        .sortedBy { it.peerNickname.lowercase() }
}
