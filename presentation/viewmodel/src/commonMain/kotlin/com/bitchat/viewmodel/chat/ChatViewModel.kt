package com.bitchat.viewmodel.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.invoke
import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.ClearMessages
import com.bitchat.domain.chat.GetAvailableNamedChannels
import com.bitchat.domain.chat.GetChannelKeyCommitment
import com.bitchat.domain.chat.GetChannelMembers
import com.bitchat.domain.chat.GetGeohashParticipants
import com.bitchat.domain.chat.GetJoinedNamedChannels
import com.bitchat.domain.chat.GetMeshPeers
import com.bitchat.domain.chat.JoinChannel
import com.bitchat.domain.chat.LeaveChannel
import com.bitchat.domain.chat.ObserveChannelMessages
import com.bitchat.domain.chat.ProcessChatCommand
import com.bitchat.domain.chat.SendMessage
import com.bitchat.domain.chat.SetChannelPassword
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.ChatCommand
import com.bitchat.domain.chat.model.CommandContext
import com.bitchat.domain.chat.model.CommandResult
import com.bitchat.domain.chat.model.failure.ChannelFailure
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.user.BlockUser
import com.bitchat.domain.user.GetBlockedUsers
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.domain.user.GetUserState
import com.bitchat.domain.user.SaveUserStateAction
import com.bitchat.domain.user.UnblockUser
import com.bitchat.domain.user.model.BlockType
import com.bitchat.domain.user.model.UserStateAction
import com.bitchat.mediautils.resolveMediaToLocalPath
import com.bitchat.viewvo.chat.ChatState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class ChatViewModel(
    private val observeChannelMessages: ObserveChannelMessages,
    private val sendMessage: SendMessage,
    private val processChatCommand: ProcessChatCommand,
    private val getUserState: GetUserState,
    private val chatEventBus: ChatEventBus,
    private val getUserNickname: GetUserNickname,
    private val saveUserStateAction: SaveUserStateAction,
    private val leaveChannel: LeaveChannel,
    private val getJoinedNamedChannels: GetJoinedNamedChannels,
    private val getGeohashParticipants: GetGeohashParticipants,
    private val getMeshPeers: GetMeshPeers,
    private val getChannelKeyCommitment: GetChannelKeyCommitment,
    private val getAvailableNamedChannels: GetAvailableNamedChannels,
    private val getChannelMembers: GetChannelMembers,
    private val blockUser: BlockUser,
    private val unblockUser: UnblockUser,
    private val getBlockedUsers: GetBlockedUsers,
    private val joinChannel: JoinChannel,
    private val setChannelPassword: SetChannelPassword,
    private val clearMessages: ClearMessages,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)

    init {
        viewModelScope.launch {
            chatEventBus.events()
                .distinctUntilChanged()
                .onStart { emit(com.bitchat.domain.chat.model.ChatEvent.ChannelChanged) }
                .collect { event ->
                    when (event) {
                        is com.bitchat.domain.chat.model.ChatEvent.ChannelChanged -> {
                            val userState = getUserState()
                            if (userState is UserState.Active) {
                                val active = userState.activeState
                                if (active is ActiveState.Chat) {
                                    val channel = active.channel
                                    _state.update {
                                        it.copy(
                                            selectedLocationChannel = channel,
                                            currentGeohash = if (channel is Channel.Location) channel.geohash else null,
                                            currentChannel = when (channel) {
                                                is Channel.Location -> channel.geohash
                                                Channel.Mesh -> null
                                                else -> null
                                            }
                                        )
                                    }

                                    _currentChannel.value = channel
                                } else {
                                    _currentChannel.value = null
                                }
                            } else {
                                _currentChannel.value = null
                            }
                        }

                        else -> {}
                    }
                }
        }

        viewModelScope.launch {
            _currentChannel
                .flatMapLatest { channel ->
                    if (channel != null) {
                        observeChannelMessages(channel)
                    } else {
                        flowOf(emptyList())
                    }
                }
                .collect { messages ->
                    _state.update { it.copy(messages = messages) }
                }
        }

        viewModelScope.launch {
            getUserNickname().collect { nickname ->
                _state.update { it.copy(nickname = nickname) }
            }
        }

        viewModelScope.launch {
            getJoinedNamedChannels().collect { channels ->
                _state.update { it.copy(joinedNamedChannels = channels) }
            }
        }
    }

    fun updateMessageInput(text: String) {
        _state.update { it.copy(messageInput = text) }
    }

    fun sendMessage() {
        val content = _state.value.messageInput.trim()
        if (content.isEmpty()) return

        viewModelScope.launch {
            try {
                val channel = when (val userState = getUserState()) {
                    is UserState.Active -> when (val active = userState.activeState) {
                        is ActiveState.Chat -> active.channel
                        else -> return@launch
                    }

                    else -> return@launch
                }

                val nickname = _state.value.nickname

                val commandResult = processChatCommand(
                    ProcessChatCommand.ChatCommandRequest(
                        input = content,
                        context = CommandContext(
                            isLocationChannel = channel is Channel.Location,
                            isMeshChannel = channel is Channel.Mesh,
                            isNamedChannel = channel is Channel.NamedChannel,
                            currentChannel = _state.value.currentChannel
                        )
                    )
                )

                val processedContent = when (commandResult) {
                    CommandResult.NotACommand -> content
                    is CommandResult.Invalid -> {
                        _state.update { it.copy(pendingCommandFailure = commandResult.failure) }
                        return@launch
                    }

                    is CommandResult.Parsed -> when (val command = commandResult.command) {
                        is ChatCommand.Hug -> {
                            val sender = nickname.ifBlank { "someone" }
                            "* $sender gives ${command.target} a warm hug *"
                        }

                        is ChatCommand.Slap -> {
                            val sender = nickname.ifBlank { "someone" }
                            val item = command.item.trim().ifBlank { "large trout" }
                            val hasArticle = item.startsWith("a ", ignoreCase = true) ||
                                    item.startsWith("an ", ignoreCase = true) ||
                                    item.startsWith("the ", ignoreCase = true)
                            val itemWithArticle = if (hasArticle) {
                                item
                            } else {
                                val article = if (item.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
                                "$article $item"
                            }
                            "* $sender slaps ${command.target} around with $itemWithArticle *"
                        }

                        is ChatCommand.Block -> {
                            handleBlockCommand(command.target, channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        is ChatCommand.Unblock -> {
                            handleUnblockCommand(command.target)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        is ChatCommand.Join -> {
                            handleJoinCommand(command.channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        is ChatCommand.Leave -> {
                            handleLeaveCommand(command.channel, channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        is ChatCommand.Pass -> {
                            handlePassCommand(command.currentPassword, command.newPassword, channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        ChatCommand.List, ChatCommand.Channels -> {
                            handleListCommand()
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        is ChatCommand.Who -> {
                            handleWhoCommand(command.channel, channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        ChatCommand.Clear -> {
                            clearConversation(channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        is ChatCommand.Message -> {
                            handleMessageCommand(command.target, command.message, channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        ChatCommand.Save, is ChatCommand.Transfer -> {
                            addSystemMessage("command not implemented yet")
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }
                    }
                }

                println("ChatViewModel: sendMessage channel=$channel contentLength=${processedContent.length}")
                _state.update { it.copy(isSending = true) }

                sendMessage(
                    SendMessage.Params(
                    content = processedContent,
                    channel = channel,
                    sender = nickname.ifEmpty { "Anonymous" }
                ))

                _state.update { it.copy(isSending = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSending = false,
                        errorMessage = "Failed to send: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private suspend fun handleBlockCommand(target: String?, channel: Channel) {
        if (target == null) {
            val blocked = getBlockedUsers()
            val message = if (blocked.isEmpty()) {
                "no blocked users"
            } else {
                "blocked users: ${blocked.joinToString(", ") { it.nickname ?: it.identifier.take(16) }}"
            }
            addSystemMessage(message)
            return
        }

        when (channel) {
            is Channel.Location -> {
                val geohash = channel.geohash
                val participants = getGeohashParticipants(geohash)
                val pubkey = participants.entries.find { (_, displayName) ->
                    displayName.equals(target, ignoreCase = true) ||
                            displayName.startsWith("$target#", ignoreCase = true)
                }?.key

                if (pubkey != null) {
                    blockUser(BlockUser.Request(pubkey, target, BlockType.GEOHASH))
                    addSystemMessage("blocked user $target")
                } else {
                    addSystemMessage("user $target not found")
                }
            }

            is Channel.Mesh -> {
                val peers = getMeshPeers()
                val peer = peers.find { it.displayName.equals(target, ignoreCase = true) }

                if (peer != null) {
                    blockUser(BlockUser.Request(peer.id, target, BlockType.MESH))
                    addSystemMessage("blocked user $target")
                } else {
                    addSystemMessage("user $target not found")
                }
            }

            else -> {
                addSystemMessage("blocking not supported in this channel")
            }
        }
    }

    private suspend fun handleUnblockCommand(target: String) {
        val blocked = getBlockedUsers()
        val blockedUser = blocked.find { user ->
            user.nickname?.equals(target, ignoreCase = true) == true ||
                    user.identifier.take(16).equals(target, ignoreCase = true)
        }

        if (blockedUser != null) {
            unblockUser(UnblockUser.Request(blockedUser.identifier, blockedUser.blockType))
            addSystemMessage("unblocked user $target")
        } else {
            addSystemMessage("user $target not in block list")
        }
    }

    private fun addSystemMessage(content: String) {
        val systemMessage = BitchatMessage(
            id = Uuid.random().toString(),
            sender = "system",
            content = content,
            type = BitchatMessageType.System,
            timestamp = Clock.System.now(),
            isPrivate = false,
            senderPeerID = null
        )
        _state.update { currentState ->
            currentState.copy(messages = currentState.messages + systemMessage)
        }
    }

    fun clearPendingCommandFailure() {
        _state.update { it.copy(pendingCommandFailure = null) }
    }

    fun postSystemMessage(message: String) {
        addSystemMessage(message)
        clearPendingCommandFailure()
    }

    private suspend fun handleJoinCommand(channelName: String) {
        when (val result = joinChannel(JoinChannel.Params(channelName = channelName))) {
            is Outcome.Success -> {
                val msg = if (result.value.isNewChannel) {
                    "created channel ${result.value.channelInfo.name}"
                } else {
                    "joined channel ${result.value.channelInfo.name}"
                }
                addSystemMessage(msg)
                saveUserStateAction(
                    UserStateAction.Chat(Channel.NamedChannel(result.value.channelInfo.name))
                )
            }

            is Outcome.Error -> {
                val msg = when (result.cause) {
                    is ChannelFailure.WrongPassword -> "wrong password"
                    is ChannelFailure.AlreadyJoined -> "already in channel"
                    else -> result.message
                }
                addSystemMessage(msg)
            }
        }
    }

    private suspend fun handleLeaveCommand(channelName: String?, currentChannel: Channel) {
        val targetChannel = channelName ?: when (currentChannel) {
            is Channel.NamedChannel -> currentChannel.channelName
            else -> {
                addSystemMessage("not in a named channel")
                return
            }
        }

        leaveChannel(targetChannel)
        addSystemMessage("left channel $targetChannel")
        val fallback = resolvePreviousOrDefaultChannel()
        saveUserStateAction(UserStateAction.Chat(fallback))
    }

    private suspend fun resolvePreviousOrDefaultChannel(): Channel {
        val userState = getUserState()
        val previous = (userState as? UserState.Active)
            ?.activeState
            ?.let { it as? ActiveState.Chat }
            ?.previousChannel
        return previous ?: Channel.Mesh
    }

    private suspend fun handlePassCommand(
        currentPassword: String?,
        newPassword: String?,
        currentChannel: Channel
    ) {
        val channelName = when (currentChannel) {
            is Channel.NamedChannel -> currentChannel.channelName
            else -> {
                addSystemMessage("not in a named channel")
                return
            }
        }

        val password = newPassword ?: currentPassword
        if (password == null) {
            addSystemMessage("usage: /pass <password> or /pass <current> <new>")
            return
        }

        val result = setChannelPassword(
            SetChannelPassword.Params(
                channelName = channelName,
                currentPassword = if (newPassword != null) currentPassword else null,
                newPassword = password
            )
        )

        when (result) {
            is Outcome.Success -> {
                val msg = if (newPassword != null) {
                    "password changed"
                } else {
                    val isNew = getChannelKeyCommitment(channelName) == null
                    if (isNew) "password set - you are now the owner" else "ownership verified"
                }
                addSystemMessage(msg)
            }

            is Outcome.Error -> {
                val msg = when (result.cause) {
                    is ChannelFailure.NotOwner -> "you are not the channel owner"
                    is ChannelFailure.WrongPassword -> "incorrect password"
                    is ChannelFailure.OnlyCreatorCanSetPassword -> "only the creator can set the initial password"
                    is ChannelFailure.ChannelNotFound -> "channel not found"
                    else -> result.message
                }
                addSystemMessage(msg)
            }
        }
    }

    private suspend fun handleListCommand() {
        val channels = getAvailableNamedChannels()
        if (channels.isEmpty()) {
            addSystemMessage("no channels available")
        } else {
            val channelList = channels.joinToString(", ") { channel ->
                val protection = if (channel.isProtected) " [protected]" else ""
                "${channel.name}$protection (${channel.memberCount})"
            }
            addSystemMessage("channels: $channelList")
        }
    }

    private suspend fun handleWhoCommand(channelName: String?, currentChannel: Channel) {
        val targetChannel = channelName ?: when (currentChannel) {
            is Channel.NamedChannel -> currentChannel.channelName
            is Channel.Location -> {
                val participants = getGeohashParticipants(currentChannel.geohash)
                if (participants.isEmpty()) {
                    addSystemMessage("no participants")
                } else {
                    addSystemMessage("participants: ${participants.values.joinToString(", ")}")
                }
                return
            }

            is Channel.Mesh -> {
                val peers = getMeshPeers()
                if (peers.isEmpty()) {
                    addSystemMessage("no peers connected")
                } else {
                    addSystemMessage("peers: ${peers.joinToString(", ") { it.displayName }}")
                }
                return
            }

            else -> {
                addSystemMessage("not in a channel")
                return
            }
        }

        val members = getChannelMembers(targetChannel)
        if (members.isEmpty()) {
            addSystemMessage("no members in $targetChannel")
        } else {
            addSystemMessage("members of $targetChannel: ${members.joinToString(", ") { it.nickname }}")
        }
    }

    private suspend fun handleMessageCommand(target: String, message: String?, currentChannel: Channel) {
        when (currentChannel) {
            is Channel.Mesh, is Channel.MeshDM -> handleMeshMessageCommand(target, message)
            is Channel.Location -> handleLocationMessageCommand(target, message, currentChannel)
            else -> addSystemMessage("direct messages are only supported from mesh or location channels")
        }
    }

    private suspend fun handleMeshMessageCommand(target: String, message: String?) {
        val peers = getMeshPeers(Unit)
        val peer = peers.find { p ->
            p.displayName.equals(target, ignoreCase = true) ||
                    p.displayName.startsWith("$target#", ignoreCase = true)
        }

        if (peer == null) {
            addSystemMessage("user $target not found")
            return
        }

        val dmChannel = Channel.MeshDM(peer.id, peer.displayName)
        saveUserStateAction(UserStateAction.Chat(dmChannel))

        if (!message.isNullOrBlank()) {
            sendMessage(
                SendMessage.Params(
                    content = message,
                    channel = dmChannel,
                    sender = _state.value.nickname
                )
            )
        } else {
            addSystemMessage("started private chat with ${peer.displayName}")
        }
    }

    private suspend fun handleLocationMessageCommand(target: String, message: String?, currentChannel: Channel.Location) {
        val participants = getGeohashParticipants(currentChannel.geohash)
        val entry = participants.entries.find { (_, displayName) ->
            displayName.equals(target, ignoreCase = true) ||
                    displayName.startsWith("$target#", ignoreCase = true)
        }

        if (entry == null) {
            addSystemMessage("user $target not found")
            return
        }

        val fullPubkey = entry.key
        val peerID = "nostr_${fullPubkey.take(16)}"
        val displayName = entry.value

        val dmChannel = Channel.NostrDM(
            peerID = peerID,
            fullPubkey = fullPubkey,
            sourceGeohash = currentChannel.geohash,
            displayName = displayName
        )

        saveUserStateAction(UserStateAction.Chat(dmChannel))

        if (!message.isNullOrBlank()) {
            sendMessage(
                SendMessage.Params(
                    content = message,
                    channel = dmChannel,
                    sender = _state.value.nickname
                )
            )
        } else {
            addSystemMessage("started private chat with $displayName")
        }
    }

    private suspend fun clearConversation(channel: Channel) {
        try {
            clearMessages(ClearMessages.Params(channel))
            _state.update { it.copy(messages = emptyList()) }
        } catch (e: Exception) {
            addSystemMessage("failed to clear: ${e.message}")
        }
    }

    fun sendVoiceNote(peer: String?, channelName: String?, filePath: String) {
        viewModelScope.launch {
            try {
                val channel = resolveChannel(peer, channelName)
                if (channel == null) {
                    _state.update { it.copy(errorMessage = "No active channel") }
                    return@launch
                }

                val nickname = _state.value.nickname
                println("ChatViewModel: sendVoiceNote channel=$channel filePath=$filePath")
                _state.update { it.copy(isSending = true) }

                sendMessage(
                    SendMessage.Params(
                        content = filePath,
                        channel = channel,
                        sender = nickname.ifEmpty { "Anonymous" },
                        messageType = BitchatMessageType.Audio
                    )
                )

                _state.update { it.copy(isSending = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSending = false,
                        errorMessage = "Failed to send voice note: ${e.message}"
                    )
                }
            }
        }
    }

    fun sendImageNote(peer: String?, channelName: String?, filePath: String) {
        viewModelScope.launch {
            try {
                val channel = resolveChannel(peer, channelName)
                if (channel == null) {
                    _state.update { it.copy(errorMessage = "No active channel") }
                    return@launch
                }

                val nickname = _state.value.nickname
                println("ChatViewModel: sendImageNote channel=$channel filePath=$filePath")
                _state.update { it.copy(isSending = true) }

                val localPath = resolveMediaToLocalPath(filePath)
                if (localPath == null) {
                    _state.update {
                        it.copy(isSending = false, errorMessage = "Failed to copy image to local storage")
                    }
                    return@launch
                }
                println("ChatViewModel: resolved image path: $localPath")

                sendMessage(
                    SendMessage.Params(
                        content = localPath,
                        channel = channel,
                        sender = nickname.ifEmpty { "Anonymous" },
                        messageType = BitchatMessageType.Image
                    )
                )

                _state.update { it.copy(isSending = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSending = false,
                        errorMessage = "Failed to send image: ${e.message}"
                    )
                }
            }
        }
    }

    private suspend fun resolveChannel(peer: String?, channelName: String?): Channel? {
        // if peer is specified, this is a direct message
        if (peer != null) {
            val peers = getMeshPeers(Unit)
            val matchedPeer = peers.find { it.id == peer || it.displayName == peer }
            if (matchedPeer != null) {
                return Channel.MeshDM(matchedPeer.id, matchedPeer.displayName)
            }
        }

        // if channel name is specified, use named channel
        if (channelName != null) {
            return Channel.NamedChannel(channelName)
        }

        // fall back to current channel
        return when (val userState = getUserState()) {
            is UserState.Active -> when (val active = userState.activeState) {
                is ActiveState.Chat -> active.channel
                else -> null
            }
            else -> null
        }
    }
}
