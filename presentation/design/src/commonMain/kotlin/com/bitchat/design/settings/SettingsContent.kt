package com.bitchat.design.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitchat.viewvo.settings.SettingsState
import com.bitchat.viewvo.settings.ThemePreference

@Composable
fun SettingsContent(
    state: SettingsState,
    onThemeSelected: (ThemePreference) -> Unit,
    onBackgroundModeToggled: (Boolean) -> Unit,
    onProofOfWorkToggled: (Boolean) -> Unit,
    onTorNetworkToggled: (Boolean) -> Unit,
    onPowDifficultyChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onShowDebug: (() -> Unit)? = null
) {
    val lazyListState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 ||
                    lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.98f else 0f,
        label = "topBarAlpha"
    )

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 80.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item(key = "header") {
                SettingsHeader(appVersion = state.appVersion)
            }

            item(key = "features") {
                SettingsFeaturesCard()
            }

            item(key = "theme") {
                SettingsThemeSection(
                    selectedTheme = state.selectedTheme,
                    onThemeSelected = onThemeSelected
                )
            }

            item(key = "toggles") {
                SettingsTogglesCard(
                    showBackgroundModeSetting = state.showBackgroundModeSetting,
                    backgroundModeEnabled = state.backgroundModeEnabled,
                    onBackgroundModeToggled = onBackgroundModeToggled,
                    proofOfWorkEnabled = state.proofOfWorkEnabled,
                    onProofOfWorkToggled = onProofOfWorkToggled,
                    torNetworkEnabled = state.torNetworkEnabled,
                    onTorNetworkToggled = onTorNetworkToggled,
                    torAvailable = state.torAvailable,
                    torRunning = state.torRunning,
                    torBootstrapPercent = state.torBootstrapPercent
                )
            }

            if (state.proofOfWorkEnabled) {
                item(key = "pow_slider") {
                    PowDifficultySlider(
                        powDifficulty = state.powDifficulty,
                        onPowDifficultyChanged = onPowDifficultyChanged
                    )
                }
            }

            if (state.torNetworkEnabled) {
                println("last logline: ${state.torLastLogLine}")
                item(key = "tor_status") {
                    TorStatusCard(
                        torRunning = state.torRunning,
                        torBootstrapPercent = state.torBootstrapPercent,
                        torLastLogLine = state.torLastLogLine
                    )
                }
            }

            item(key = "warning") {
                EmergencyWarningCard()
            }

            item(key = "footer") {
                SettingsFooter(onShowDebug = onShowDebug)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha))
        ) {
            CloseButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(horizontal = 16.dp)
            )
        }
    }
}
