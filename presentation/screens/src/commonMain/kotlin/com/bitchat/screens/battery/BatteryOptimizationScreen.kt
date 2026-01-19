package com.bitchat.screens.battery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bitchat.design.battery.BatteryOptimizationContent
import com.bitchat.domain.app.model.BatteryOptimizationStatus
import com.bitchat.viewmodel.battery.BatteryOptimizationViewModel

@Composable
fun BatteryOptimizationScreen(
    status: BatteryOptimizationStatus,
    viewModel: BatteryOptimizationViewModel,
) {

    BatteryOptimizationContent(
        modifier = Modifier.fillMaxSize(),
        status = status,
        onDisableBatteryOptimization = viewModel::onDisableBatteryOptimizationClick,
        onRetry = viewModel::onRetryClicked,
        onSkip = viewModel::onSkipBatteryOptimizationClick,
    )
}
