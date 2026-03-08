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
import com.bitchat.domain.lora.GetLoRaSettings
import com.bitchat.domain.lora.SetLoRaEnabled
import com.bitchat.domain.lora.SetLoRaRegion
import com.bitchat.domain.lora.SetLoRaTxPower
import com.bitchat.domain.lora.SetShowLoRaPeers
import com.bitchat.domain.lora.SwitchLoRaProtocol
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
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
    private val getLoRaSettings: GetLoRaSettings,
    private val setLoRaEnabled: SetLoRaEnabled,
    private val setLoRaRegion: SetLoRaRegion,
    private val setLoRaTxPower: SetLoRaTxPower,
    private val setShowLoRaPeers: SetShowLoRaPeers,
    private val switchLoRaProtocol: SwitchLoRaProtocol,
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

            val loraSettings = getLoRaSettings?.invoke()

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
                    loraAvailable = getLoRaSettings != null,
                    loraEnabled = loraSettings?.enabled ?: true,
                    loraRegion = loraSettings?.region ?: LoRaRegion.US_915,
                    loraTxPower = loraSettings?.txPower ?: LoRaTxPower.MEDIUM,
                    loraShowPeers = loraSettings?.showPeers ?: true,
                    loraProtocol = loraSettings?.protocol ?: LoRaProtocolType.BITCHAT,
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

    fun onLoRaEnabledToggled(enabled: Boolean) {
        viewModelScope.launch {
            setLoRaEnabled.invoke(enabled)
            _state.update { it.copy(loraEnabled = enabled) }
        }
    }

    fun onLoRaRegionSelected(region: LoRaRegion) {
        viewModelScope.launch {
            val currentPower = _state.value.loraTxPower
            setLoRaRegion.invoke(SetLoRaRegion.Params(region, currentPower))
            _state.update { it.copy(loraRegion = region) }
        }
    }

    fun onLoRaTxPowerSelected(power: LoRaTxPower) {
        viewModelScope.launch {
            val currentRegion = _state.value.loraRegion
            setLoRaTxPower.invoke(SetLoRaTxPower.Params(power, currentRegion))
            _state.update { it.copy(loraTxPower = power) }
        }
    }

    fun onLoRaShowPeersToggled(show: Boolean) {
        viewModelScope.launch {
            setShowLoRaPeers.invoke(show)
            _state.update { it.copy(loraShowPeers = show) }
        }
    }

    fun onLoRaProtocolSelected(protocol: LoRaProtocolType) {
        viewModelScope.launch {
            switchLoRaProtocol.invoke(protocol)
            _state.update { it.copy(loraProtocol = protocol) }
        }
    }
}
