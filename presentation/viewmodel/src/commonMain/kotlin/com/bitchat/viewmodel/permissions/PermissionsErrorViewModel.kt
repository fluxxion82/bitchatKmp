package com.bitchat.viewmodel.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.viewvo.permissions.PermissionType
import kotlinx.coroutines.launch

class PermissionsErrorViewModel(
    val deniedPermissions: List<PermissionType>,
    private val saveUserStateAction: SaveUserStateAction,
) : ViewModel() {
    fun onRetryClick() {
        viewModelScope.launch {
            saveUserStateAction(UserStateAction.Locations)
        }
    }
}
