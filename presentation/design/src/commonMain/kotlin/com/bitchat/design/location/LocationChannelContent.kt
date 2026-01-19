package com.bitchat.design.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.bookmarked
import bitchatkmp.presentation.design.generated.resources.checking_permissions
import bitchatkmp.presentation.design.generated.resources.finding_nearby_channels
import bitchatkmp.presentation.design.generated.resources.grant_location_permission
import bitchatkmp.presentation.design.generated.resources.location_bluetooth_subtitle
import bitchatkmp.presentation.design.generated.resources.location_channels_desc
import bitchatkmp.presentation.design.generated.resources.location_channels_title
import bitchatkmp.presentation.design.generated.resources.location_level_block
import bitchatkmp.presentation.design.generated.resources.location_level_city
import bitchatkmp.presentation.design.generated.resources.location_level_neighborhood
import bitchatkmp.presentation.design.generated.resources.location_level_province
import bitchatkmp.presentation.design.generated.resources.location_level_region
import bitchatkmp.presentation.design.generated.resources.location_permission_denied
import bitchatkmp.presentation.design.generated.resources.location_permission_granted
import bitchatkmp.presentation.design.generated.resources.mesh_label
import bitchatkmp.presentation.design.generated.resources.open_settings
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.viewvo.location.LocationChannelsState
import org.jetbrains.compose.resources.stringResource

@Composable
fun LocationChannelContent(
    state: LocationChannelsState,
    permissionState: PermissionState?,
    onSelectMesh: () -> Unit,
    onSelectChannel: (GeohashChannel) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onCustomGeohashChange: (String) -> Unit,
    onTeleport: () -> Unit,
    onOpenMap: () -> Unit,
    onToggleLocationServices: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val listState = rememberLazyListState()
    val standardGreen = Color(0xFF32D74B)
    val standardBlue = Color(0xFF007AFF)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        color = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.location_channels_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = stringResource(Res.string.location_channels_desc),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                }
            }

            if (state.locationServicesEnabled) {
                item(key = "permissions") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        when (permissionState) {
                            PermissionState.NOT_DETERMINED -> {
                                Button(
                                    onClick = onRequestPermission,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = standardGreen.copy(alpha = 0.12f),
                                        contentColor = standardGreen
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(Res.string.grant_location_permission),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            PermissionState.DENIED,
                            PermissionState.RESTRICTED -> {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(Res.string.location_permission_denied),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Red.copy(alpha = 0.8f)
                                    )
                                    androidx.compose.material3.TextButton(
                                        onClick = onOpenSettings
                                    ) {
                                        Text(
                                            text = stringResource(Res.string.open_settings),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            PermissionState.AUTHORIZED -> {
                                Text(
                                    text = stringResource(Res.string.location_permission_granted),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = standardGreen
                                )
                            }

                            null -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp))
                                    Text(
                                        text = stringResource(Res.string.checking_permissions),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Mesh channel
            item(key = "mesh") {
                ChannelRow(
                    title = meshTitleWithCount(state.meshParticipantCount),
                    subtitle = stringResource(
                        Res.string.location_bluetooth_subtitle,
                        bluetoothRangeString()
                    ),
                    isSelected = state.selectedChannel is Channel.Mesh,
                    titleColor = standardBlue,
                    titleBold = state.meshParticipantCount > 0,
                    trailingContent = null,
                    onClick = { onSelectMesh() }
                )
            }

            if (state.locationServicesEnabled) {
                val nearbyChannels = state.availableChannels.filter {
                    it.level != GeohashChannelLevel.BUILDING
                }
                items(nearbyChannels, key = { "nearby-${it.geohash}" }) { channel ->
                    val participantCount = state.participantCounts[channel.geohash] ?: 0
                    NearbyChannelRow(
                        channel = channel,
                        participantCount = participantCount,
                        locationName = state.locationNames[channel.level],
                        isSelected = isChannelSelected(channel, state.selectedChannel),
                        isBookmarked = state.bookmarkedGeohashes.contains(channel.geohash),
                        onSelect = { onSelectChannel(channel) },
                        onToggleBookmark = { onToggleBookmark(channel.geohash) },
                        titleColor = standardGreen
                    )
                }
                if (nearbyChannels.isEmpty() && permissionState == PermissionState.AUTHORIZED) {
                    item(key = "finding_nearby") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(Res.string.finding_nearby_channels),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            if (state.bookmarkedGeohashes.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.bookmarked),
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }

                items(state.bookmarkedGeohashes, key = { "bookmarked-$it" }) { geohash ->
                    BookmarkedChannelRow(
                        geohash = geohash,
                        participantCount = state.participantCounts[geohash] ?: 0,
                        locationName = state.bookmarkNames[geohash],
                        isSelected = isBookmarkedSelected(geohash, state.selectedChannel),
                        title = geohashHashTitleWithCount(geohash, state.participantCounts[geohash] ?: 0),
                        onSelect = {
                            val level = levelForLength(geohash.length)
                            val channel = GeohashChannel(level, geohash)
                            onSelectChannel(channel)
                        },
                        onRemoveBookmark = { onToggleBookmark(geohash) }
                    )
                }
            }

            item {
                CustomGeohashInput(
                    value = state.customGeohash,
                    error = state.customGeohashError,
                    onValueChange = { onCustomGeohashChange(it) },
                    onTeleport = { onTeleport() },
                    onOpenMap = { onOpenMap() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }

            item {
                LocationServicesToggle(
                    enabled = state.locationServicesEnabled,
                    onToggle = { onToggleLocationServices() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun meshTitleWithCount(count: Int): String {
    val peopleText = formatPeopleCount(count)
    val meshLabel = stringResource(Res.string.mesh_label)
    return "$meshLabel [$peopleText]"
}

@Composable
private fun isChannelSelected(channel: GeohashChannel, selected: Channel): Boolean {
    return when (selected) {
        is Channel.Location -> selected.geohash == channel.geohash
        else -> false
    }
}

@Composable
private fun isBookmarkedSelected(geohash: String, selected: Channel): Boolean {
    return when (selected) {
        is Channel.Location -> selected.geohash == geohash
        else -> false
    }
}

private fun levelForLength(length: Int): GeohashChannelLevel {
    return when (length) {
        in 0..2 -> GeohashChannelLevel.REGION
        in 3..4 -> GeohashChannelLevel.PROVINCE
        5 -> GeohashChannelLevel.CITY
        6 -> GeohashChannelLevel.NEIGHBORHOOD
        7 -> GeohashChannelLevel.BLOCK
        8 -> GeohashChannelLevel.BUILDING
        else -> if (length > 8) GeohashChannelLevel.BUILDING else GeohashChannelLevel.BLOCK
    }
}

@Composable
private fun BookmarkedChannelRow(
    geohash: String,
    participantCount: Int,
    locationName: String?,
    isSelected: Boolean,
    title: String,
    onSelect: () -> Unit,
    onRemoveBookmark: () -> Unit
) {
    val level = levelForLength(geohash.length)
    val channel = GeohashChannel(level, geohash)

    NearbyChannelRow(
        channel = channel,
        participantCount = participantCount,
        locationName = locationName,
        isSelected = isSelected,
        isBookmarked = true,  // Always bookmarked in this list
        onSelect = onSelect,
        onToggleBookmark = onRemoveBookmark,
        titleOverride = title,
        titleColor = null
    )
}

@Composable
private fun geohashHashTitleWithCount(geohash: String, participantCount: Int): String {
    val peopleText = formatPeopleCount(participantCount)
    return "#$geohash [$peopleText]"
}

@Composable
internal fun geohashTitleWithCount(level: GeohashChannelLevel, participantCount: Int): String {
    val peopleText = formatPeopleCount(participantCount)
    val levelName = when (level) {
        GeohashChannelLevel.BUILDING -> "Building"
        GeohashChannelLevel.BLOCK -> stringResource(Res.string.location_level_block)
        GeohashChannelLevel.NEIGHBORHOOD -> stringResource(Res.string.location_level_neighborhood)
        GeohashChannelLevel.CITY -> stringResource(Res.string.location_level_city)
        GeohashChannelLevel.PROVINCE -> stringResource(Res.string.location_level_province)
        GeohashChannelLevel.REGION -> stringResource(Res.string.location_level_region)
    }
    return "$levelName [$peopleText]"
}

private fun bluetoothRangeString(): String {
    return "~10-50 m"
}

private fun formatPeopleCount(count: Int): String {
    return if (count == 1) "1 person" else "$count people"
}
