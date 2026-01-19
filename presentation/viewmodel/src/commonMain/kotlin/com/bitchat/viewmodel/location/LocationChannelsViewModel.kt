package com.bitchat.viewmodel.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.invoke
import com.bitchat.domain.location.BeginGeohashSampling
import com.bitchat.domain.location.EndGeohashSampling
import com.bitchat.domain.location.GetAvailableChannels
import com.bitchat.domain.location.GetBookmarkNames
import com.bitchat.domain.location.GetBookmarkedChannels
import com.bitchat.domain.location.GetLocationNames
import com.bitchat.domain.location.GetLocationServicesEnabled
import com.bitchat.domain.location.GetParticipantCounts
import com.bitchat.domain.location.GetTeleportState
import com.bitchat.domain.location.ResolveLocationName
import com.bitchat.domain.location.ToggleBookmark
import com.bitchat.domain.location.ToggleLocationServices
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.user.GetUserState
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.viewvo.location.LocationChannelsEffect
import com.bitchat.viewvo.location.LocationChannelsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LocationChannelsViewModel(
    private val saveUserStateAction: SaveUserStateAction,
    private val getAvailableChannels: GetAvailableChannels,
    private val getParticipantCounts: GetParticipantCounts,
    private val getBookmarkedChannels: GetBookmarkedChannels,
    private val beginGeohashSampling: BeginGeohashSampling,
    private val endGeohashSampling: EndGeohashSampling,
    private val toggleBookmark: ToggleBookmark,
    private val toggleLocationServices: ToggleLocationServices,
    private val getBookmarkNames: GetBookmarkNames,
    private val getTeleportState: GetTeleportState,
    private val getLocationServicesEnabled: GetLocationServicesEnabled,
    private val getLocationNames: GetLocationNames,
    private val resolveLocationName: ResolveLocationName,
    private val getUserState: GetUserState,
) : ViewModel() {
    private val _state = MutableStateFlow(LocationChannelsState())
    val state: StateFlow<LocationChannelsState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<LocationChannelsEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<LocationChannelsEffect> = _effects.asSharedFlow()

    private var refreshJob: Job? = null

    init {
        println("🎬 [LocationChannelsViewModel] Initializing...")
        loadInitialData()
        startLiveRefresh()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                val channels = getAvailableChannels()
                val counts = getParticipantCounts()
                val bookmarks = getBookmarkedChannels()
                val bookmarkNames = getBookmarkNames()
                val selectedChannel = when (val state = getUserState()) {
                    is UserState.Active -> {
                        val active = state.activeState
                        if (active is ActiveState.Chat) {
                            active.channel
                        } else Channel.Mesh
                    }

                    else -> Channel.Mesh
                }
                val isTeleported = getTeleportState()
                val locationServicesEnabled = getLocationServicesEnabled()
                val locationNames = getLocationNames()

                _state.update {
                    it.copy(
                        availableChannels = channels,
                        participantCounts = counts.geohashCounts,
                        meshParticipantCount = counts.meshCount,
                        bookmarkedGeohashes = bookmarks,
                        bookmarkNames = bookmarkNames,
                        selectedChannel = selectedChannel,
                        isTeleported = isTeleported,
                        locationServicesEnabled = locationServicesEnabled,
                        locationNames = locationNames,
                        isLoading = false
                    )
                }

                resolveLocationNamesInBackground(channels, locationNames)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun startLiveRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)

                if (_state.value.locationServicesEnabled) {
                    refreshData()
                }
            }
        }
    }

    private suspend fun refreshData() {
        _state.update { it.copy(isRefreshing = true) }

        try {
            val channels = getAvailableChannels(Unit)
            val counts = getParticipantCounts(Unit)
            val locationNames = getLocationNames(Unit)

            _state.update {
                it.copy(
                    availableChannels = channels,
                    participantCounts = counts.geohashCounts,
                    meshParticipantCount = counts.meshCount,
                    locationNames = locationNames,
                    isRefreshing = false
                )
            }

            resolveLocationNamesInBackground(channels, locationNames)
        } catch (e: Exception) {
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    fun startGeohashSampling(geohashes: List<String>) {
        viewModelScope.launch {
            beginGeohashSampling(geohashes)
        }
    }

    fun endGeohashSampling() {
        viewModelScope.launch {
            endGeohashSampling(Unit)
        }
    }

    fun onSelectMesh() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    selectedChannel = Channel.Mesh,
                    isTeleported = false
                )
            }

            saveUserStateAction(UserStateAction.Chat(Channel.Mesh))
        }
    }

    fun onSelectChannel(channel: GeohashChannel) {
        viewModelScope.launch {
            // Determine if this is a teleport
            val currentChannels = _state.value.availableChannels
            val isTeleport = !currentChannels.any { it.geohash == channel.geohash }

            val locationChannel = Channel.Location(channel.level, channel.geohash)

            _state.update {
                it.copy(
                    selectedChannel = locationChannel,
                    isTeleported = isTeleport
                )
            }

            saveUserStateAction(UserStateAction.Chat(Channel.Location(channel.level, channel.geohash), isTeleport))
        }
    }

    fun onToggleBookmark(geohash: String) {
        viewModelScope.launch {
            toggleBookmark(geohash)

            val bookmarks = getBookmarkedChannels(Unit)
            val bookmarkNames = getBookmarkNames(Unit)
            _state.update {
                it.copy(
                    bookmarkedGeohashes = bookmarks,
                    bookmarkNames = bookmarkNames
                )
            }
        }
    }

    fun onCustomGeohashChange(value: String) {
        val filtered = normalizeGeohash(value)

        _state.update {
            it.copy(
                customGeohash = filtered,
                customGeohashError = null
            )
        }
    }

    fun onTeleport() {
        viewModelScope.launch {
            val geohash = _state.value.customGeohash.trim()

            if (geohash.length < 2) {
                _state.update { it.copy(customGeohashError = "invalid geohash") }
                return@launch
            }

            val level = geohashLevel(geohash)

            val locationChannel = Channel.Location(level, geohash)

            _state.update {
                it.copy(
                    selectedChannel = locationChannel,
                    isTeleported = true,
                    customGeohash = "",
                    customGeohashError = null
                )
            }

            saveUserStateAction(UserStateAction.Chat(Channel.Location(level, geohash), true))
        }
    }

    fun onToggleLocationServices() {
        viewModelScope.launch {
            toggleLocationServices(Unit)
            val enabled = getLocationServicesEnabled(Unit)

            _state.update { it.copy(locationServicesEnabled = enabled) }

            if (enabled) {
                refreshData()
            }
        }
    }

    fun onOpenMap() {
        viewModelScope.launch {
            val initial = _state.value.customGeohash.ifBlank {
                val selected = _state.value.selectedChannel
                if (selected is Channel.Location) selected.geohash else null
            }
            _effects.tryEmit(LocationChannelsEffect.OpenMap(initial?.ifBlank { null }))
        }
    }

    fun onMapResult(geohash: String) {
        viewModelScope.launch {
            val normalized = normalizeGeohash(geohash)

            if (normalized.length < 2) {
                _state.update {
                    it.copy(
                        customGeohash = normalized,
                        customGeohashError = "invalid geohash"
                    )
                }
                return@launch
            }

            val level = geohashLevel(normalized)
            val locationChannel = Channel.Location(level, normalized)

            _state.update {
                it.copy(
                    selectedChannel = locationChannel,
                    isTeleported = true,
                    customGeohash = normalized,
                    customGeohashError = null
                )
            }

            saveUserStateAction(UserStateAction.Chat(locationChannel, true))
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }

    private fun resolveLocationNamesInBackground(
        channels: List<GeohashChannel>,
        existingNames: Map<GeohashChannelLevel, String>
    ) {
        viewModelScope.launch {
            val updated = existingNames.toMutableMap()
            for (channel in channels) {
                if (!updated.containsKey(channel.level)) {
                    resolveLocationName(ResolveLocationName.Params(channel.geohash, channel.level))?.let { name ->
                        updated[channel.level] = name
                        // Update state incrementally as each name is resolved
                        _state.update { it.copy(locationNames = updated.toMap()) }
                    }
                }
            }
        }
    }

    private fun normalizeGeohash(input: String): String {
        val allowed = "0123456789bcdefghjkmnpqrstuvwxyz".toSet()
        return input
            .lowercase()
            .replace("#", "")
            .filter { it in allowed }
            .take(12)
    }

    private fun geohashLevel(geohash: String): GeohashChannelLevel {
        return when (geohash.length) {
            in 2..3 -> GeohashChannelLevel.REGION
            4 -> GeohashChannelLevel.PROVINCE
            5 -> GeohashChannelLevel.CITY
            6 -> GeohashChannelLevel.NEIGHBORHOOD
            7 -> GeohashChannelLevel.BLOCK
            else -> GeohashChannelLevel.BUILDING
        }
    }
}
