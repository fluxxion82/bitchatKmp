package com.bitchat.domain.app.model

import com.bitchat.domain.location.model.Channel

sealed interface AppState

sealed interface UserState : AppState {
    data object PermissionsRequired : UserState
    data object BluetoothDisabled : UserState
    data object LocationServicesDisabled : UserState
    data class BatteryOptimization(val status: BatteryOptimizationStatus) : UserState
    data class Active(val activeState: ActiveState) : UserState
}

sealed interface ActiveState : AppState {
    data class Chat(
        val channel: Channel,
        val previousChannel: Channel? = null
    ) : ActiveState

    data object Locations : ActiveState
    data object Settings : ActiveState
    data class LocationNotes(
        val previousChannel: Channel? = null
    ) : ActiveState
}
