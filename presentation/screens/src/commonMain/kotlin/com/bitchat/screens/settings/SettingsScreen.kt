package com.bitchat.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import com.bitchat.design.settings.SettingsContent
import com.bitchat.viewmodel.settings.SettingsViewModel

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel,
) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state = state,
        onThemeSelected = viewModel::onThemeSelected,
        onBackgroundModeToggled = viewModel::onBackgroundModeToggled,
        onProofOfWorkToggled = viewModel::onProofOfWorkToggled,
        onTorNetworkToggled = viewModel::onTorNetworkToggled,
        onPowDifficultyChanged = viewModel::onPowDifficultyChanged,
        onLoRaEnabledToggled = viewModel::onLoRaEnabledToggled,
        onLoRaRegionSelected = viewModel::onLoRaRegionSelected,
        onLoRaTxPowerSelected = viewModel::onLoRaTxPowerSelected,
        onLoRaShowPeersToggled = viewModel::onLoRaShowPeersToggled,
        onLoRaProtocolSelected = viewModel::onLoRaProtocolSelected,
        onDismiss = { navController.navigateUp() },
        onShowDebug = null  // TODO: Wire when debug settings ready
    )
}
