package com.bitchat.viewmodel.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.location.GetPermissionState
import com.bitchat.domain.location.RequestLocationPermission
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.viewmodel.navigation.PermissionError
import com.bitchat.viewmodel.navigation.PermissionsNavigation
import com.bitchat.viewvo.permissions.PermissionStatus
import com.bitchat.viewvo.permissions.PermissionType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PermissionsViewModel(
    private val saveUserStateAction: SaveUserStateAction,
    private val getPermissionState: GetPermissionState,
    private val requestLocationPermissionUsecase: RequestLocationPermission,
) : ViewModel() {
    val navigation = Channel<PermissionsNavigation>(Channel.RENDEZVOUS)

    val permissionStates = MutableStateFlow<Map<PermissionType, PermissionStatus>>(emptyMap())

    val requiredPermissions = setOf(PermissionType.NEARBY_DEVICES, PermissionType.PRECISE_LOCATION)
    val optionalPermissions = setOf(PermissionType.NOTIFICATIONS)

    fun updatePermissionStatus(permissionType: PermissionType, status: PermissionStatus) {
        permissionStates.update { current ->
            current + (permissionType to status)
        }

        checkPermissionsAndNavigate()
    }

    private fun checkPermissionsAndNavigate() {
        val states = permissionStates.value
        val allResponded = requiredPermissions.all { it in states } && optionalPermissions.all { it in states }

        if (!allResponded) return
        val anyRequiredDenied = requiredPermissions.any {
            states[it] == PermissionStatus.DENIED
        }

        viewModelScope.launch {
            if (anyRequiredDenied) {
                val deniedList = requiredPermissions.filter { states[it] == PermissionStatus.DENIED }
                // clear out denied so we can try again
                val granted = permissionStates.value.filter { it.value == PermissionStatus.GRANTED }
                permissionStates.emit(granted)

                navigation.send(PermissionError(deniedList))
            } else {
                saveUserStateAction(UserStateAction.GrantPermissions)
            }
        }
    }

    suspend fun isLocationPermissionGranted(): Boolean {
        val state = getPermissionState(Unit)
        return state == PermissionState.AUTHORIZED
    }

    fun requestLocationPermission() {
        viewModelScope.launch {
            requestLocationPermissionUsecase(Unit)
            val state = getPermissionState(Unit)
            val status = if (state == PermissionState.AUTHORIZED) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }
            updatePermissionStatus(PermissionType.PRECISE_LOCATION, status)
        }
    }
}
