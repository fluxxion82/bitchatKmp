package com.bitchat.domain.user

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.BatteryOptimizationStatus
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.repository.UserRepository

val DEFAULT_CHANNEL = Channel.Mesh

class GetUserState(
    private val userRepository: UserRepository,
    private val appRepository: AppRepository,
    private val connectivityRepository: ConnectivityRepository,
) : Usecase<Unit, UserState> {
    override suspend fun invoke(param: Unit): UserState {
        return if (!appRepository.hasRequiredPermissions()) {
            UserState.PermissionsRequired
        } else if (!connectivityRepository.isBluetoothEnabled()) {
            UserState.BluetoothDisabled
        } else if (!connectivityRepository.isLocationServicesEnabled()) {
            UserState.LocationServicesDisabled
        } else if (!appRepository.isBatteryOptimizationSkipped()
            && appRepository.getBatteryOptimizationStatus() == BatteryOptimizationStatus.ENABLED
        ) {
            UserState.BatteryOptimization(BatteryOptimizationStatus.ENABLED)
        } else {
            val cached = userRepository.getUserState()
            cached.also { println("get user state, cached state is $cached") } ?: UserState.Active(ActiveState.Chat(DEFAULT_CHANNEL))
        }
    }
}
