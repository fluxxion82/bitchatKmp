package com.bitchat.screens.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitchat.design.location.LocationChannelContent
import com.bitchat.design.location.MapPickerLauncher
import com.bitchat.design.location.rememberMapPickerLauncher
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.screens.permissions.PermissionCallback
import com.bitchat.screens.permissions.createPermissionsManager
import com.bitchat.viewmodel.location.LocationChannelsViewModel
import com.bitchat.viewvo.location.LocationChannelsEffect
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType

@Composable
fun LocationChannelsScreen(
    viewModel: LocationChannelsViewModel,
) {
    val state by viewModel.state.collectAsState()
    var permissionStatus by remember { mutableStateOf<PermissionStatus?>(null) }
    var hasRequestedPermission by remember { mutableStateOf(false) }
    var hasCheckedPermission by remember { mutableStateOf(false) }
    var shouldRequestPermission by remember { mutableStateOf(false) }
    var shouldOpenSettings by remember { mutableStateOf(false) }

    val permissionsManager = createPermissionsManager(object : PermissionCallback {
        override fun onPermissionStatus(permissionType: PermissionType, status: PermissionStatus) {
            if (permissionType == PermissionType.PRECISE_LOCATION) {
                permissionStatus = status
            }
        }
    })

    val isPermissionGranted = permissionsManager.isPermissionGranted(PermissionType.PRECISE_LOCATION)
    LaunchedEffect(isPermissionGranted) {
        hasCheckedPermission = true
        if (isPermissionGranted) {
            permissionStatus = PermissionStatus.GRANTED
        }
    }

    if (shouldRequestPermission) {
        shouldRequestPermission = false
        permissionsManager.askPermission(PermissionType.PRECISE_LOCATION)
    }

    if (shouldOpenSettings) {
        shouldOpenSettings = false
        permissionsManager.launchSettings()
    }

    val permissionState = when {
        !hasCheckedPermission -> null
        permissionStatus == PermissionStatus.GRANTED -> PermissionState.AUTHORIZED
        permissionStatus == PermissionStatus.DENIED && hasRequestedPermission -> PermissionState.DENIED
        else -> PermissionState.NOT_DETERMINED
    }

    val geohashesToSample = remember(state.availableChannels, state.bookmarkedGeohashes) {
        (state.availableChannels.map { it.geohash } + state.bookmarkedGeohashes)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    LaunchedEffect(geohashesToSample) {
        viewModel.startGeohashSampling(geohashesToSample)
    }

    val mapPickerLauncher: MapPickerLauncher = rememberMapPickerLauncher()
    LaunchedEffect(viewModel, mapPickerLauncher) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is LocationChannelsEffect.OpenMap -> mapPickerLauncher.open(effect.initialGeohash, viewModel::onMapResult)
                is LocationChannelsEffect.ApplyMapResult -> viewModel.onMapResult(effect.geohash)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.endGeohashSampling()
        }
    }

    LocationChannelContent(
        state = state,
        permissionState = permissionState,
        onSelectMesh = {
            viewModel.onSelectMesh()
        },
        onSelectChannel = { channel ->
            viewModel.onSelectChannel(channel)
        },
        onToggleBookmark = viewModel::onToggleBookmark,
        onCustomGeohashChange = viewModel::onCustomGeohashChange,
        onTeleport = {
            viewModel.onTeleport()
        },
        onOpenMap = viewModel::onOpenMap,
        onToggleLocationServices = viewModel::onToggleLocationServices,
        onRequestPermission = {
            hasRequestedPermission = true
            shouldRequestPermission = true
        },
        onOpenSettings = { shouldOpenSettings = true },
    )
}
