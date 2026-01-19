package com.bitchat.viewmodel.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.app.DisableBatteryOptimization
import com.bitchat.domain.app.SkipBatteryOptimization
import com.bitchat.domain.base.invoke
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.model.UserStateAction
import kotlinx.coroutines.launch

class BatteryOptimizationViewModel(
    private val saveUserStateAction: SaveUserStateAction,
    private val skipBatteryOptimization: SkipBatteryOptimization,
    private val disableBatteryOptimization: DisableBatteryOptimization,
) : ViewModel() {
    fun onSkipBatteryOptimizationClick() {
        viewModelScope.launch {
            skipBatteryOptimization()
            saveUserStateAction(UserStateAction.HandledOptimizations)
        }
    }

    fun onRetryClicked() {
        viewModelScope.launch {
            saveUserStateAction(UserStateAction.GrantPermissions)
        }
    }

    fun onDisableBatteryOptimizationClick() {
        viewModelScope.launch {
            disableBatteryOptimization()
            saveUserStateAction(UserStateAction.HandledOptimizations)
        }
    }
}
