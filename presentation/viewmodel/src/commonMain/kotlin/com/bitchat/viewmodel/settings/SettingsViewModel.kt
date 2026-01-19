package com.bitchat.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.app.DisableBackgroundMode
import com.bitchat.domain.app.EnableBackgroundMode
import com.bitchat.domain.app.GetAppTheme
import com.bitchat.domain.app.GetBackgroundMode
import com.bitchat.domain.app.SetAppTheme
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.model.BackgroundMode
import com.bitchat.domain.base.invoke
import com.bitchat.domain.nostr.GetPowSettings
import com.bitchat.domain.nostr.SetPowSettings
import com.bitchat.domain.nostr.model.PowSettings
import com.bitchat.domain.tor.DisableTor
import com.bitchat.domain.tor.EnableTor
import com.bitchat.domain.tor.GetTorMode
import com.bitchat.domain.tor.GetTorStatus
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.viewvo.settings.SettingsState
import com.bitchat.viewvo.settings.ThemePreference
import com.bitchat.viewvo.settings.toDomain
import com.bitchat.viewvo.settings.toThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val setAppTheme: SetAppTheme,
    private val getAppTheme: GetAppTheme,
    private val setPowSettings: SetPowSettings,
    private val getPowSettings: GetPowSettings,
    private val getTorStatus: GetTorStatus,
    private val getTorMode: GetTorMode,
    private val enableTor: EnableTor,
    private val disableTor: DisableTor,
    private val getBackgroundMode: GetBackgroundMode,
    private val enableBackgroundMode: EnableBackgroundMode,
    private val disableBackgroundMode: DisableBackgroundMode,
    private val showBackgroundModeSetting: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        loadInitialState()
        observeTorStatus()
        observeTorMode()
        observeBackgroundMode()
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val theme = getAppTheme().firstOrNull() ?: AppTheme.SYSTEM
            val themePreference = theme.toThemePreference()

            val powSettings = getPowSettings().firstOrNull() ?: PowSettings()

            val torMode = getTorMode().firstOrNull() ?: TorMode.OFF

            val backgroundMode = getBackgroundMode().firstOrNull() ?: BackgroundMode.OFF

            _state.update {
                it.copy(
                    appVersion = "1.5.1",
                    selectedTheme = themePreference,
                    showBackgroundModeSetting = showBackgroundModeSetting,
                    proofOfWorkEnabled = powSettings.enabled,
                    powDifficulty = powSettings.difficulty,
                    backgroundModeEnabled = backgroundMode == BackgroundMode.ON,
                    torNetworkEnabled = torMode == TorMode.ON,
                    torAvailable = true,
                )
            }
        }
    }

    private fun observeTorStatus() {
        viewModelScope.launch {
            getTorStatus().collect { torStatus ->
                _state.update {
                    it.copy(
                        torRunning = torStatus.running,
                        torBootstrapPercent = torStatus.bootstrapPercent,
                        torLastLogLine = torStatus.lastLogLine
                    )
                }
            }
        }
    }

    private fun observeTorMode() {
        viewModelScope.launch {
            getTorMode().collect { torMode ->
                _state.update {
                    it.copy(
                        torNetworkEnabled = torMode == TorMode.ON
                    )
                }
            }
        }
    }

    private fun observeBackgroundMode() {
        viewModelScope.launch {
            getBackgroundMode().collect { backgroundMode ->
                _state.update {
                    it.copy(
                        backgroundModeEnabled = backgroundMode == BackgroundMode.ON
                    )
                }
            }
        }
    }

    fun onThemeSelected(theme: ThemePreference) {
        viewModelScope.launch {
            setAppTheme(theme.toDomain())
            _state.update { it.copy(selectedTheme = theme) }
        }
    }

    fun onBackgroundModeToggled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                enableBackgroundMode()
            } else {
                disableBackgroundMode()
            }
        }
    }

    fun onProofOfWorkToggled(enabled: Boolean) {
        viewModelScope.launch {
            val currentSettings = getPowSettings().firstOrNull() ?: PowSettings()
            setPowSettings(currentSettings.copy(enabled = enabled))
            _state.update { it.copy(proofOfWorkEnabled = enabled) }
        }
    }

    fun onTorNetworkToggled(enabled: Boolean) {
        if (_state.value.torAvailable) {
            _state.update { it.copy(torNetworkEnabled = enabled) }

            viewModelScope.launch {
                if (enabled) {
                    enableTor()
                } else {
                    disableTor()
                }
            }
        }
    }

    fun onPowDifficultyChanged(difficulty: Int) {
        viewModelScope.launch {
            val currentSettings = getPowSettings().firstOrNull() ?: PowSettings()
            setPowSettings(currentSettings.copy(difficulty = difficulty))
            _state.update { it.copy(powDifficulty = difficulty.coerceIn(0, 32)) }
        }
    }
}
