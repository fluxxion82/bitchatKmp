package com.bitchat.viewmodel.navigation

import com.bitchat.domain.app.model.BatteryOptimizationStatus
import com.bitchat.domain.location.model.Channel
import com.bitchat.viewvo.permissions.PermissionType

sealed interface MainNavigation
sealed interface PermissionsNavigation

data object Back : MainNavigation

data class PermissionError(val deniedPermissions: List<PermissionType>) : PermissionsNavigation

data class OptimizationsSuggested(val status: BatteryOptimizationStatus) : MainNavigation
data object PermissionsRequest : MainNavigation
data object BluetoothDisabled : MainNavigation
data object LocationServicesDisabled : MainNavigation

data class Chat(val channel: String) : MainNavigation
data object Locations : MainNavigation
object Settings : MainNavigation
data object LocationNotes : MainNavigation

fun Channel.toNavigationString(): String = when (this) {
    is Channel.Location -> geohash
    Channel.Mesh -> "mesh"
    is Channel.MeshDM -> peerID
    is Channel.NostrDM -> peerID
    is Channel.NamedChannel -> channelName
}
