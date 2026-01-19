package com.bitchat.viewmodel.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.model.UserStateAction
import kotlinx.coroutines.launch

class LocationDisabledViewModel(
    private val saveUserStateAction: SaveUserStateAction,
) : ViewModel() {
    fun onRetryClick() {
        viewModelScope.launch {
            saveUserStateAction(UserStateAction.Locations)
        }
    }
}