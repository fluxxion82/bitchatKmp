package com.bitchat.repo.di

import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.connectivity.repository.ConnectivityRepository
import com.bitchat.domain.initialization.AppInitializer
import com.bitchat.domain.user.repository.UserRepository

class BluetoothMeshAppInitializer(
    private val bluetoothMeshService: BluetoothMeshService,
    private val appRepository: AppRepository,
    private val connectivityRepository: ConnectivityRepository,
    private val userRepository: UserRepository,
) : AppInitializer {

    override suspend fun initialize() {
        val userState = userRepository.getUserState()
        val hasPermissions = appRepository.hasRequiredPermissions()
        val bluetoothEnabled = connectivityRepository.isBluetoothEnabled()

        if (userState is UserState.Active && hasPermissions && bluetoothEnabled) {
            bluetoothMeshService.startServices()
            println("BluetoothMeshService started at app launch")
        } else {
            println("BluetoothMeshService NOT started: state=$userState, permissions=$hasPermissions, bt=$bluetoothEnabled")
        }
    }
}
