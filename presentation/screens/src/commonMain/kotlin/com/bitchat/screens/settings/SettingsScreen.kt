package com.bitchat.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import com.bitchat.design.settings.SettingsContent
import com.bitchat.screens.Platform
import com.bitchat.screens.getPlatform
import com.bitchat.viewmodel.settings.SettingsViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = koinViewModel { parametersOf(getPlatform() == Platform.ANDROID) }
) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state = state,
        onThemeSelected = viewModel::onThemeSelected,
        onBackgroundModeToggled = viewModel::onBackgroundModeToggled,
        onProofOfWorkToggled = viewModel::onProofOfWorkToggled,
        onTorNetworkToggled = viewModel::onTorNetworkToggled,
        onPowDifficultyChanged = viewModel::onPowDifficultyChanged,
        onDismiss = { navController.navigateUp() },
        onShowDebug = null  // TODO: Wire when debug settings ready
    )
}
