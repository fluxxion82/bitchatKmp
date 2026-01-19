package com.bitchat.viewmodel.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.app.ClearAllData
import com.bitchat.domain.app.GetAppTheme
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.invoke
import com.bitchat.domain.chat.GetJoinedChannels
import com.bitchat.domain.chat.LeaveChannel
import com.bitchat.domain.chat.MarkPrivateChatRead
import com.bitchat.domain.chat.ObserveLatestUnreadPrivatePeer
import com.bitchat.domain.chat.ObservePeerSessionStates
import com.bitchat.domain.chat.ObservePrivateChats
import com.bitchat.domain.chat.ObserveUnreadPrivatePeers
import com.bitchat.domain.location.ObserveChannelParticipants
import com.bitchat.domain.location.ObserveCurrentChannelBookmarkState
import com.bitchat.domain.location.ObserveHasNotes
import com.bitchat.domain.location.ObserveLocationServicesEnabled
import com.bitchat.domain.location.ObservePermissionState
import com.bitchat.domain.location.ToggleBookmark
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.nostr.GetPowSettings
import com.bitchat.domain.tor.GetTorStatus
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.domain.user.GetAllFavorites
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.domain.user.GetUserState
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.SetUserNickname
import com.bitchat.domain.user.ToggleFavorite
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.viewmodel.navigation.Back
import com.bitchat.viewmodel.navigation.BluetoothDisabled
import com.bitchat.viewmodel.navigation.Chat
import com.bitchat.viewmodel.navigation.LocationNotes
import com.bitchat.viewmodel.navigation.LocationServicesDisabled
import com.bitchat.viewmodel.navigation.Locations
import com.bitchat.viewmodel.navigation.MainNavigation
import com.bitchat.viewmodel.navigation.OptimizationsSuggested
import com.bitchat.viewmodel.navigation.PermissionsRequest
import com.bitchat.viewmodel.navigation.Settings
import com.bitchat.viewmodel.navigation.toNavigationString
import com.bitchat.viewvo.chat.HeaderState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import com.bitchat.domain.location.model.Channel as BitchatChannel

class MainViewModel(
    private val getUserState: GetUserState,
    private val getAppTheme: GetAppTheme,
    private val getPowSettings: GetPowSettings,
    private val getTorStatus: GetTorStatus,
    private val userEventBus: UserEventBus,
    private val getJoinedChannels: GetJoinedChannels,
    private val getUserNickname: GetUserNickname,
    private val observePrivateChats: ObservePrivateChats,
    private val observeUnreadPrivatePeers: ObserveUnreadPrivatePeers,
    private val observeLatestUnreadPrivatePeer: ObserveLatestUnreadPrivatePeer,
    private val observePeerSessionStates: ObservePeerSessionStates,
    private val observeLocationServicesEnabled: ObserveLocationServicesEnabled,
    private val observePermissionState: ObservePermissionState,
    private val observeCurrentChannelBookmarkState: ObserveCurrentChannelBookmarkState,
    private val observeHasNotes: ObserveHasNotes,
    private val observeChannelParticipants: ObserveChannelParticipants,
    private val getAllFavorites: GetAllFavorites,
    private val toggleFavoriteUseCase: ToggleFavorite,
    private val leaveChannel: LeaveChannel,
    private val setUserNickname: SetUserNickname,
    private val toggleBookmarkUseCase: ToggleBookmark,
    private val saveUserStateAction: SaveUserStateAction,
    private val markPrivateChatRead: MarkPrivateChatRead,
    private val clearAllData: ClearAllData,
) : ViewModel() {
    val navigation = Channel<MainNavigation>(Channel.RENDEZVOUS)

    val appTheme = MutableStateFlow(AppTheme.SYSTEM)

    private val _headerState = MutableStateFlow(HeaderState())
    val headerState: StateFlow<HeaderState> = _headerState.asStateFlow()
    private var latestUnreadPrivatePeer: String? = null

    init {
        viewModelScope.launch {
            userEventBus.events()
                .onStart { goToNextStep() }
                .collect { event ->
                    when (event) {
                        UserEvent.StateChanged,
                        is UserEvent.LoginChanged -> goToNextStep()

                        is UserEvent.FavoriteStatusChanged -> refreshFavorites()
                        else -> Unit
                    }
                }
        }

        viewModelScope.launch {
            getAppTheme().collect {
                appTheme.emit(it)
            }
        }

        viewModelScope.launch {
            getPowSettings().collect { powSettings ->
                _headerState.update {
                    it.copy(
                        powEnabled = powSettings.enabled,
                        powDifficulty = powSettings.difficulty,
                        isMining = powSettings.isMining
                    )
                }
            }
        }

        viewModelScope.launch {
            getJoinedChannels().collect { channels ->
                _headerState.update { it.copy(joinedChannels = channels) }
            }
        }

        viewModelScope.launch {
            getUserNickname().collect { nickname ->
                _headerState.update { it.copy(nickname = nickname) }
            }
        }

        viewModelScope.launch {
            observePrivateChats().collect { chats ->
                _headerState.update { current ->
                    val dmPeers = chats.keys
                    val shouldMerge = current.selectedLocationChannel !is BitchatChannel.Location
                    val connected = if (shouldMerge) {
                        (current.connectedPeers + dmPeers).distinct()
                    } else {
                        current.connectedPeers
                    }
                    val nicknames = if (shouldMerge) {
                        val dmNames = dmPeers.associateWith { peerID ->
                            val messages = chats[peerID].orEmpty()
                            val recentOther = messages.lastOrNull { it.sender != current.nickname }
                            recentOther?.sender ?: current.peerNicknames[peerID] ?: peerID.take(12)
                        }
                        dmNames + current.peerNicknames
                    } else {
                        current.peerNicknames
                    }
                    logConnectedPeersState(
                        reason = "privateChats",
                        channelDesc = describeChannel(current.selectedLocationChannel),
                        isMeshChannel = current.selectedLocationChannel is BitchatChannel.Mesh ||
                                current.selectedLocationChannel is BitchatChannel.MeshDM,
                        connected = connected,
                        people = emptyList()
                    )
                    current.copy(
                        connectedPeers = connected,
                        peerNicknames = nicknames
                    )
                }
            }
        }

        viewModelScope.launch {
            observeUnreadPrivatePeers().collect { unreadPeers ->
                _headerState.update { it.copy(hasUnreadPrivateMessages = unreadPeers.isNotEmpty()) }
            }
        }

        viewModelScope.launch {
            observeLatestUnreadPrivatePeer().collect { latest ->
                latestUnreadPrivatePeer = latest
            }
        }

        viewModelScope.launch {
            observePeerSessionStates().collect { states ->
                _headerState.update { it.copy(peerSessionStates = states) }
            }
        }

        viewModelScope.launch {
            observeLocationServicesEnabled().collect { enabled ->
                _headerState.update { it.copy(locationServicesEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            observePermissionState().collect { state ->
                _headerState.update { it.copy(permissionState = state) }
            }
        }

        viewModelScope.launch {
            observeCurrentChannelBookmarkState().collect { isBookmarked ->
                _headerState.update { it.copy(isCurrentChannelBookmarked = isBookmarked) }
            }
        }

        viewModelScope.launch {
            observeHasNotes().collect { hasNotes ->
                _headerState.update { it.copy(hasNotes = hasNotes) }
            }
        }

        viewModelScope.launch {
            observeChannelParticipants().collect { people ->
                _headerState.update { current ->
                    val isMeshChannel = current.selectedLocationChannel is BitchatChannel.Mesh ||
                            current.selectedLocationChannel is BitchatChannel.MeshDM

                    val connected = if (isMeshChannel) {
                        people.map { it.id }
                    } else {
                        people.map { it.displayName }
                    }
                    val peerNicknames = if (isMeshChannel) {
                        people.associate { it.id to it.displayName }
                    } else {
                        people.associate { "nostr_${it.id.take(16)}" to it.displayName }
                    }
                    val channelDesc = describeChannel(current.selectedLocationChannel)
                    logConnectedPeersState(
                        reason = "channelParticipants",
                        channelDesc = channelDesc,
                        isMeshChannel = isMeshChannel,
                        connected = connected,
                        people = people
                    )
                    current.copy(
                        geohashPeople = people,
                        connectedPeers = connected,
                        peerNicknames = peerNicknames,
                        peerDirect = people.map { it.id }.associateWith {
                            isMeshChannel
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            getTorStatus().collect { torStatus ->
                _headerState.update {
                    it.copy(
                        torEnabled = torStatus.mode == TorMode.ON,
                        torRunning = torStatus.running,
                        torBootstrapPercent = torStatus.bootstrapPercent
                    )
                }
            }
        }

        viewModelScope.launch {
            refreshFavorites()
        }
    }

    fun goToNextStep() {
        viewModelScope.launch {
            when (val userState = getUserState()) {
                is UserState.Active -> {
                    when (val activeState = userState.activeState) {
                        is ActiveState.Chat -> {
                            val channel = activeState.channel
                            println("user state is active chat, channel: $channel")
                            when (channel) {
                                is BitchatChannel.Location -> {
                                    _headerState.update {
                                        it.copy(
                                            selectedLocationChannel = channel,
                                            selectedChannel = channel,
                                            selectedPrivatePeer = null,
                                            currentChannel = null,
                                        )
                                    }
                                }

                                BitchatChannel.Mesh -> {
                                    _headerState.update {
                                        it.copy(
                                            selectedLocationChannel = channel,
                                            selectedChannel = channel,
                                            selectedPrivatePeer = null,
                                            currentChannel = null,
                                        )
                                    }
                                }

                                is BitchatChannel.NamedChannel -> {
                                    _headerState.update {
                                        it.copy(
                                            selectedChannel = channel,
                                            currentChannel = channel.channelName,
                                            selectedPrivatePeer = null
                                        )
                                    }
                                }

                                is BitchatChannel.MeshDM -> _headerState.update {
                                    it.copy(
                                        selectedChannel = channel,
                                        currentChannel = channel.peerID,
                                        selectedPrivatePeer = channel.peerID,
                                    )
                                }

                                is BitchatChannel.NostrDM -> _headerState.update {
                                    it.copy(
                                        selectedChannel = channel,
                                        currentChannel = channel.peerID,
                                        selectedPrivatePeer = channel.peerID,
                                    )
                                }
                            }
                            navigation.send(Chat(activeState.channel.toNavigationString()))
                        }

                        is ActiveState.LocationNotes -> navigation.send(LocationNotes)
                        ActiveState.Locations -> navigation.send(Locations)
                        ActiveState.Settings -> navigation.send(Settings)
                    }
                }

                is UserState.BatteryOptimization -> navigation.send(OptimizationsSuggested(userState.status))
                UserState.BluetoothDisabled -> navigation.send(BluetoothDisabled)
                UserState.LocationServicesDisabled -> navigation.send(LocationServicesDisabled)
                UserState.PermissionsRequired -> navigation.send(PermissionsRequest)
            }
        }
    }

    fun goBack() {
        viewModelScope.launch {
            when (val userState = getUserState()) {
                UserState.PermissionsRequired,
                UserState.BluetoothDisabled,
                UserState.LocationServicesDisabled,
                is UserState.BatteryOptimization -> navigation.send(Back)

                is UserState.Active -> {
                    when (val activeState = userState.activeState) {
                        is ActiveState.Chat -> {
                            when (val channel = activeState.channel) {
                                is BitchatChannel.Location -> navigation.send(Back)
                                BitchatChannel.Mesh -> navigation.send(Back)
                                is BitchatChannel.NamedChannel -> {
                                    val previous = activeState.previousChannel
                                    saveUserStateAction(
                                        com.bitchat.domain.user.model.UserStateAction.Chat(previous ?: BitchatChannel.Mesh)
                                    )
                                }

                                is BitchatChannel.MeshDM -> {
                                    println("private mesh dm, peer id: ${channel.peerID}")
                                    println("private mesh dm, header state: ${_headerState.value}")
                                    println("private mesh dm, header state selectedLocationChannel: ${_headerState.value.selectedLocationChannel}")
                                    println("private mesh dm, header state currentChannel: ${_headerState.value.currentChannel}")

                                    viewModelScope.launch {
                                        markPrivateChatRead(channel.peerID)
                                    }

                                    saveUserStateAction(
                                        UserStateAction.Chat(BitchatChannel.Mesh)
                                    )
                                }

                                is BitchatChannel.NostrDM -> {
                                    println("private nostr dm, peer id: ${channel.peerID}")
                                    println("private nostr dm, header state: ${_headerState.value}")
                                    println("private nostr dm, header state selectedLocationChannel: ${_headerState.value.selectedLocationChannel}")
                                    println("private nostr dm, header state currentChannel: ${_headerState.value.currentChannel}")

                                    viewModelScope.launch {
                                        markPrivateChatRead(channel.peerID)
                                    }

                                    val previous = activeState.previousChannel
                                    saveUserStateAction(
                                        UserStateAction.Chat(previous ?: BitchatChannel.Mesh)
                                    )
                                }
                            }
                        }

                        is ActiveState.LocationNotes -> {
                            val previous = activeState.previousChannel
                            saveUserStateAction(
                                UserStateAction.Chat(previous ?: BitchatChannel.Mesh)
                            )
                        }

                        ActiveState.Locations -> navigation.send(Back)
                        ActiveState.Settings -> navigation.send(Back)
                    }
                }
            }
        }
    }

    fun updateNickname(newNickname: String) {
        viewModelScope.launch {
            setUserNickname(newNickname)
        }
    }

    fun toggleSidebar() {
        _headerState.update { it.copy(showSidebar = !it.showSidebar) }
    }

    fun toggleFavorite(peerID: String) {
        viewModelScope.launch {
            val nickname = _headerState.value.peerNicknames[peerID]
                ?: getCurrentNickname(peerID)
                ?: peerID

            toggleFavoriteUseCase(ToggleFavorite.Params(peerID = peerID, peerNickname = nickname))
            refreshFavorites()
        }
    }

    fun toggleBookmark(geohash: String) {
        viewModelScope.launch {
            toggleBookmarkUseCase(geohash)
        }
    }

    fun leaveNamedChannel(channelName: String) {
        viewModelScope.launch {
            val fallbackChannel = resolvePreviousOrDefaultChannel()
            leaveChannel(channelName)
            saveUserStateAction(
                UserStateAction.Chat(fallbackChannel)
            )
        }
    }

    fun selectChannel(channelName: String) {
        viewModelScope.launch {
            saveUserStateAction(
                UserStateAction.Chat(BitchatChannel.NamedChannel(channelName))
            )
        }
    }

    fun startGeohashDM(person: GeoPerson, sourceGeohash: String?) {
        viewModelScope.launch {
            saveUserStateAction(
                UserStateAction.NostrDM(
                    fullPubkey = person.id,
                    sourceGeohash = sourceGeohash,
                    displayName = person.displayName
                )
            )
        }
    }

    fun startMeshDM(peerID: String, nickname: String) {
        viewModelScope.launch {
            _headerState.update {
                it.copy(
                    peerNicknames = it.peerNicknames + (peerID to nickname)
                )
            }

            saveUserStateAction(
                UserStateAction.MeshDM(
                    peerID = peerID,
                    displayName = nickname,
                )
            )
        }
    }

    fun showAppInfo() {
        viewModelScope.launch {
            navigation.send(Settings)
        }
    }

    fun showLocationChannels() {
        viewModelScope.launch {
            navigation.send(Locations)
        }
    }

    fun showLocationNotes() {
        viewModelScope.launch {
            saveUserStateAction(UserStateAction.LocationNotes)
        }
    }

    private suspend fun resolvePreviousOrDefaultChannel(): BitchatChannel {
        val userState = getUserState()
        val previous = (userState as? UserState.Active)
            ?.activeState
            ?.let { it as? ActiveState.Chat }
            ?.previousChannel
        return previous ?: BitchatChannel.Mesh
    }

    fun openLatestUnreadDM() {
        viewModelScope.launch {
            println("openLatestUnreadDM(), latestUnreadPrivatePeer: $latestUnreadPrivatePeer")
            latestUnreadPrivatePeer?.let { peerID ->
                if (peerID.startsWith("nostr_")) {
                    // Nostr DM - try to find the person in geohashPeople
                    val person = _headerState.value.geohashPeople.find {
                        "nostr_${it.id.take(16)}" == peerID
                    }

                    if (person != null) {
                        // Found in current geohash - use full person info
                        val sourceGeohash = _headerState.value.selectedLocationChannel.let { ch ->
                            when (ch) {
                                is BitchatChannel.Location -> ch.geohash
                                else -> null
                            }
                        }
                        startGeohashDM(person, sourceGeohash)
                    } else {
                        // person not in current geohash (might have left or DM from different location)
                        // pass just peerID - SaveUserStateAction will lookup the rest from ChatRepo
                        saveUserStateAction(
                            UserStateAction.NostrDM(
                                peerID = peerID
                            )
                        )
                    }
                } else {
                    val displayName = _headerState.value.peerNicknames[peerID]
                    saveUserStateAction(
                        UserStateAction.MeshDM(
                            peerID = peerID,
                            displayName = displayName,
                        )
                    )
                }
            }
        }
    }

    private suspend fun refreshFavorites() {
        _headerState.update { current ->
            val favorites = getAllFavorites()
            val favoriteState = buildFavoriteState(favorites, current.peerNicknames)
            current.copy(
                favoritePeers = favoriteState.favoritePeers,
                favoriteRelationships = favoriteState.favoriteRelationships,
                peerNicknames = favoriteState.peerNicknames
            )
        }
    }

    private fun getCurrentNickname(peerID: String): String? {
        return _headerState.value.peerNicknames[peerID]
            ?: _headerState.value.geohashPeople.firstOrNull { it.id == peerID }?.displayName
    }

    fun handleTripleClick() {
        viewModelScope.launch {
            clearAllData()
            goToNextStep()
        }
    }
}

private fun describeChannel(channel: BitchatChannel?): String = when (channel) {
    is BitchatChannel.Location -> "Location(${channel.level.name}:${channel.geohash.take(6)})"
    BitchatChannel.Mesh -> "Mesh"
    is BitchatChannel.MeshDM -> "MeshDM(${channel.peerID.take(12)})"
    is BitchatChannel.NostrDM -> {
        val sourceLabel = channel.sourceGeohash ?: "direct"
        "NostrDM(${channel.peerID.take(16)} / $sourceLabel)"
    }

    is BitchatChannel.NamedChannel -> "Named(${channel.channelName})"
    null -> "Unknown"
}

private fun logConnectedPeersState(
    reason: String,
    channelDesc: String,
    isMeshChannel: Boolean,
    connected: List<String>,
    people: List<GeoPerson>
) {
    val peopleSummary = people.take(6).joinToString(", ") { it.displayName }.ifEmpty { "none" }
    val connectedSummary = connected.take(6).joinToString(", ").ifEmpty { "none" }
    val morePeople = if (people.size > 6) " +${people.size - 6} more" else ""
    val moreConnected = if (connected.size > 6) " +${connected.size - 6} more" else ""
    println("[CONNECTED-PEERS] ${Clock.System.now()} reason=$reason channel=$channelDesc mesh=$isMeshChannel count=${connected.size} people=$peopleSummary$morePeople connected=$connectedSummary$moreConnected")
}

internal fun buildFavoriteState(
    favorites: Map<String, FavoriteRelationship>,
    currentPeerNicknames: Map<String, String>
): FavoriteState {
    val normalized = favorites.mapKeys { it.key.lowercase() }
    val favoritesOnly = normalized.filterValues { it.isFavorite }
    val favoritePeers = favoritesOnly.keys
    val favoriteNicknames = favoritesOnly.mapValues { it.value.peerNickname }
    val mergedNicknames = favoriteNicknames + currentPeerNicknames

    return FavoriteState(
        favoritePeers = favoritePeers.toSet(),
        favoriteRelationships = normalized,
        peerNicknames = mergedNicknames
    )
}

internal data class FavoriteState(
    val favoritePeers: Set<String>,
    val favoriteRelationships: Map<String, FavoriteRelationship>,
    val peerNicknames: Map<String, String>
)
