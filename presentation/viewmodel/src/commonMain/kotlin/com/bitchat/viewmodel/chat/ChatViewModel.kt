package com.bitchat.viewmodel.chat

import com.bitchat.domain.base.logPath
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.invoke
import com.bitchat.domain.base.model.Outcome
import com.bitchat.domain.chat.ChatNotices
import com.bitchat.domain.chat.ResolveChatFallback
import com.bitchat.domain.chat.conversationKey
import com.bitchat.domain.chat.isSameConversation
import com.bitchat.domain.chat.mergeNotices
import com.bitchat.domain.chat.ClearMessages
import com.bitchat.domain.chat.GetAvailableNamedChannels
import com.bitchat.domain.chat.GetChannelKeyCommitment
import com.bitchat.domain.chat.GetChannelMembers
import com.bitchat.domain.chat.GetGeohashParticipants
import com.bitchat.domain.chat.GetJoinedNamedChannels
import com.bitchat.domain.chat.FindMeshPeerByName
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
import com.bitchat.viewvo.chat.PendingCommandFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant
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
    private val findMeshPeerByName: FindMeshPeerByName,
    private val getChannelKeyCommitment: GetChannelKeyCommitment,
    private val getAvailableNamedChannels: GetAvailableNamedChannels,
    private val getChannelMembers: GetChannelMembers,
    private val blockUser: BlockUser,
    private val unblockUser: UnblockUser,
    private val getBlockedUsers: GetBlockedUsers,
    private val joinChannel: JoinChannel,
    private val setChannelPassword: SetChannelPassword,
    private val clearMessages: ClearMessages,
    private val resolveChatFallback: ResolveChatFallback,
    private val chatNotices: ChatNotices,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)

    init {
        viewModelScope.launch {
            // Every ChannelChanged is handled, even two in a row: SaveUserStateAction announces a
            // switch before it saves the new user state and again after, and the state read for
            // the first can still be the old one. Dropping repeats (as distinctUntilChanged did)
            // left the list on the old channel while the header and sends had moved on.
            chatEventBus.events()
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
                            } else if (userState is UserState.BluetoothDisabled) {
                                // When Bluetooth is disabled, still observe Mesh channel for LoRa messages
                                println("📡 ChatViewModel: Bluetooth disabled, observing Mesh channel for LoRa")
                                _currentChannel.value = Channel.Mesh
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
                        // Command feedback lives in ChatNotices, for the life of the app rather
                        // than of this screen, and is merged into the chat it belongs to.
                        combine(observeChannelMessages(channel), chatNotices.notices) { messages, lines ->
                            mergeNotices(messages, lines.of(channel.conversationKey()))
                        }
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

    /**
     * Sends the line being typed, and answers whether it was taken. A caller clears its editor
     * only when it was: nothing may run against stores the wipe has only half cleared, and a line
     * refused for that would otherwise be lost between the editor and here. The refusal itself is
     * on [ChatState.errorMessage], where a refused DM leaves one too.
     */
    fun sendMessage(): Boolean {
        val content = _state.value.messageInput.trim()
        if (content.isEmpty()) return false
        if (chatNotices.resetting) {
            _state.update { it.copy(errorMessage = DATA_BEING_CLEARED) }
            return false
        }

        println("📤 ChatViewModel: sendMessage() called with content length=${content.length}")

        // The command answers under the identity it was asked by: a wipe part way through drops
        // whatever it would have filed (see ChatNotices.Epoch).
        viewModelScope.launch(chatNotices.epoch()) {
            try {
                val userState = getUserState()
                println("📤 ChatViewModel: userState=$userState")

                val channel = when (userState) {
                    is UserState.Active -> when (val active = userState.activeState) {
                        is ActiveState.Chat -> {
                            println("📤 ChatViewModel: In Chat state, channel=${active.channel}")
                            active.channel
                        }
                        else -> {
                            println("📤 ChatViewModel: Not in Chat state, activeState=$active - BAILING OUT")
                            return@launch
                        }
                    }

                    // When Bluetooth is disabled, allow sending via LoRa on Mesh channel
                    is UserState.BluetoothDisabled -> {
                        println("📤 ChatViewModel: Bluetooth disabled, using Mesh channel for LoRa-only")
                        Channel.Mesh
                    }

                    else -> {
                        println("📤 ChatViewModel: User not active (state=$userState) - BAILING OUT")
                        return@launch
                    }
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
                        // This coroutine's own epoch, the one it started with, not a fresh read:
                        // a reset during the suspensions above must not be papered over here.
                        val epoch = currentCoroutineContext()[ChatNotices.Epoch]
                        _state.update { it.copy(pendingCommandFailure = PendingCommandFailure(commandResult.failure, channel, epoch)) }
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
                            handleUnblockCommand(command.target, channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }

                        is ChatCommand.Join -> {
                            handleJoinCommand(command.channel, channel)
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
                            handleListCommand(channel)
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
                            addSystemMessage("command not implemented yet", channel = channel)
                            _state.update { it.copy(messageInput = "") }
                            return@launch
                        }
                    }
                }

                println("📤 ChatViewModel: About to call sendMessage - channel=$channel contentLength=${processedContent.length}")
                _state.update { it.copy(isSending = true) }

                sendMessage(
                    SendMessage.Params(
                    content = processedContent,
                    channel = channel,
                    sender = nickname.ifEmpty { "Anonymous" }
                ))

                println("📤 ChatViewModel: sendMessage completed successfully")
                _state.update { it.copy(isSending = false) }
            } catch (e: Exception) {
                println("📤 ChatViewModel: sendMessage EXCEPTION: ${e.message}")
                e.printStackTrace()
                _state.update {
                    it.copy(
                        isSending = false,
                        errorMessage = "Failed to send: ${e.message}"
                    )
                }
            }
        }
        return true
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
            addSystemMessage(message, channel = channel)
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
                    addSystemMessage("blocked user $target", channel = channel)
                } else {
                    addSystemMessage("user $target not found", channel = channel)
                }
            }

            is Channel.Mesh -> {
                val peer = findMeshPeerByName(target)

                if (peer != null) {
                    blockUser(BlockUser.Request(peer.id, target, BlockType.MESH))
                    addSystemMessage("blocked user $target", channel = channel)
                } else {
                    addSystemMessage("user $target not found", channel = channel)
                }
            }

            else -> {
                addSystemMessage("blocking not supported in this channel", channel = channel)
            }
        }
    }

    private suspend fun handleUnblockCommand(target: String, typedIn: Channel) {
        val blocked = getBlockedUsers()
        val blockedUser = blocked.find { user ->
            user.nickname?.equals(target, ignoreCase = true) == true ||
                    user.identifier.take(16).equals(target, ignoreCase = true)
        }

        if (blockedUser != null) {
            unblockUser(UnblockUser.Request(blockedUser.identifier, blockedUser.blockType))
            addSystemMessage("unblocked user $target", channel = typedIn)
        } else {
            addSystemMessage("user $target not in block list", channel = typedIn)
        }
    }

    /**
     * Shows [content] as a system line in [channel]'s messages, where it stays, in time order,
     * across updates of that chat and switches away and back. [channel] is the chat the command
     * was typed in or the one it is about, never "whatever is on screen now": a command suspends,
     * and the user can move to another chat while it runs.
     */
    private suspend fun addSystemMessage(content: String, channel: Channel) {
        val systemMessage = BitchatMessage(
            id = Uuid.random().toString(),
            sender = "system",
            content = content,
            type = BitchatMessageType.System,
            timestamp = Clock.System.now(),
            isPrivate = false,
            senderPeerID = null
        )
        chatNotices.add(channel, systemMessage, notBefore = newestMoment(channel))
    }

    /**
     * The newest moment in [channel]'s conversation, so a line filed for it goes below what is
     * already there whatever timestamps those messages carry. Always [channel]'s own conversation,
     * never the one on screen: `/j` files its "joined channel" line for the channel it is about,
     * and the user may be looking at a third chat by the time a command finishes. The chat on
     * screen is read from the state the screen already holds, since it has the notices merged in.
     */
    private suspend fun newestMoment(channel: Channel): Instant? =
        if (_currentChannel.value?.isSameConversation(channel) == true) {
            _state.value.messages.maxOfOrNull { it.timestamp }
        } else {
            observeChannelMessages(channel).first().maxOfOrNull { it.timestamp }
        }

    fun clearPendingCommandFailure() {
        _state.update { it.copy(pendingCommandFailure = null) }
    }

    /**
     * Shows [message] for [pending] as a system line in the chat the command was typed in, and
     * takes the failure off the state. The screen turns a failure into its message a frame or
     * more later, so the line must never land in whatever chat is on screen by then; a failure
     * the view model has already replaced or cleared is dropped.
     */
    fun postCommandFailure(pending: PendingCommandFailure, message: String) {
        if (_state.value.pendingCommandFailure != pending) return
        clearPendingCommandFailure()
        viewModelScope.launch(pending.epoch ?: EmptyCoroutineContext) { addSystemMessage(message, pending.channel) }
    }

    private suspend fun handleJoinCommand(channelName: String, typedIn: Channel) {
        when (val result = joinChannel(JoinChannel.Params(channelName = channelName))) {
            is Outcome.Success -> {
                val msg = if (result.value.isNewChannel) {
                    "created channel ${result.value.channelInfo.name}"
                } else {
                    "joined channel ${result.value.channelInfo.name}"
                }
                addSystemMessage(msg, channel = Channel.NamedChannel(result.value.channelInfo.name))
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
                addSystemMessage(msg, channel = typedIn)
            }
        }
    }

    private suspend fun handleLeaveCommand(channelName: String?, currentChannel: Channel) {
        val targetChannel = channelName ?: when (currentChannel) {
            is Channel.NamedChannel -> currentChannel.channelName
            else -> {
                addSystemMessage("not in a named channel", channel = currentChannel)
                return
            }
        }

        leaveChannel(targetChannel)
        val fallback = resolveChatFallback(Channel.NamedChannel(targetChannel))
        addSystemMessage("left channel $targetChannel", channel = fallback)
        saveUserStateAction(UserStateAction.Chat(fallback))
    }

    private suspend fun handlePassCommand(
        currentPassword: String?,
        newPassword: String?,
        currentChannel: Channel
    ) {
        val channelName = when (currentChannel) {
            is Channel.NamedChannel -> currentChannel.channelName
            else -> {
                addSystemMessage("not in a named channel", channel = currentChannel)
                return
            }
        }

        val password = newPassword ?: currentPassword
        if (password == null) {
            addSystemMessage("usage: /pass <password> or /pass <current> <new>", channel = currentChannel)
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
                addSystemMessage(msg, channel = currentChannel)
            }

            is Outcome.Error -> {
                val msg = when (result.cause) {
                    is ChannelFailure.NotOwner -> "you are not the channel owner"
                    is ChannelFailure.WrongPassword -> "incorrect password"
                    is ChannelFailure.OnlyCreatorCanSetPassword -> "only the creator can set the initial password"
                    is ChannelFailure.ChannelNotFound -> "channel not found"
                    else -> result.message
                }
                addSystemMessage(msg, channel = currentChannel)
            }
        }
    }

    private suspend fun handleListCommand(typedIn: Channel) {
        val channels = getAvailableNamedChannels()
        if (channels.isEmpty()) {
            addSystemMessage("no channels available", channel = typedIn)
        } else {
            val channelList = channels.joinToString(", ") { channel ->
                val protection = if (channel.isProtected) " [protected]" else ""
                "${channel.name}$protection (${channel.memberCount})"
            }
            addSystemMessage("channels: $channelList", channel = typedIn)
        }
    }

    private suspend fun handleWhoCommand(channelName: String?, currentChannel: Channel) {
        val targetChannel = channelName ?: when (currentChannel) {
            is Channel.NamedChannel -> currentChannel.channelName
            is Channel.Location -> {
                val participants = getGeohashParticipants(currentChannel.geohash)
                if (participants.isEmpty()) {
                    addSystemMessage("no participants", channel = currentChannel)
                } else {
                    addSystemMessage("participants: ${participants.values.joinToString(", ")}", channel = currentChannel)
                }
                return
            }

            is Channel.Mesh -> {
                val peers = getMeshPeers()
                if (peers.isEmpty()) {
                    addSystemMessage("no peers connected", channel = currentChannel)
                } else {
                    addSystemMessage("peers: ${peers.joinToString(", ") { it.displayName }}", channel = currentChannel)
                }
                return
            }

            else -> {
                addSystemMessage("not in a channel", channel = currentChannel)
                return
            }
        }

        val members = getChannelMembers(targetChannel)
        if (members.isEmpty()) {
            addSystemMessage("no members in $targetChannel", channel = currentChannel)
        } else {
            addSystemMessage("members of $targetChannel: ${members.joinToString(", ") { it.nickname }}", channel = currentChannel)
        }
    }

    private suspend fun handleMessageCommand(target: String, message: String?, currentChannel: Channel) {
        when (currentChannel) {
            is Channel.Mesh, is Channel.MeshDM -> handleMeshMessageCommand(target, message, currentChannel)
            is Channel.Location -> handleLocationMessageCommand(target, message, currentChannel)
            else -> addSystemMessage("direct messages are only supported from mesh or location channels", channel = currentChannel)
        }
    }

    private suspend fun handleMeshMessageCommand(target: String, message: String?, typedIn: Channel) {
        val peer = findMeshPeerByName(target)

        if (peer == null) {
            addSystemMessage("user $target not found", channel = typedIn)
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
            addSystemMessage("started private chat with ${peer.displayName}", channel = typedIn)
        }
    }

    private suspend fun handleLocationMessageCommand(target: String, message: String?, currentChannel: Channel.Location) {
        val participants = getGeohashParticipants(currentChannel.geohash)
        val entry = participants.entries.find { (_, displayName) ->
            displayName.equals(target, ignoreCase = true) ||
                    displayName.startsWith("$target#", ignoreCase = true)
        }

        if (entry == null) {
            addSystemMessage("user $target not found", channel = currentChannel)
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
            addSystemMessage("started private chat with $displayName", channel = currentChannel)
        }
    }

    private suspend fun clearConversation(channel: Channel) {
        try {
            clearMessages(ClearMessages.Params(channel)) // Drops the channel's notices too.
            _state.update { it.copy(messages = emptyList()) }
        } catch (e: Exception) {
            addSystemMessage("failed to clear: ${e.message}", channel = channel)
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
                println("ChatViewModel: sendVoiceNote channel=$channel filePath=${logPath(filePath)}")
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
                println("ChatViewModel: sendImageNote channel=$channel filePath=${logPath(filePath)}")
                _state.update { it.copy(isSending = true) }

                val localPath = resolveMediaToLocalPath(filePath)
                if (localPath == null) {
                    _state.update {
                        it.copy(isSending = false, errorMessage = "Failed to copy image to local storage")
                    }
                    return@launch
                }
                println("ChatViewModel: resolved image path: ${logPath(localPath)}")

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
            // What is handed in here is the key of the private chat that is open. It means the connected
            // peer with exactly that id and is never read as a name: a peer can announce any text, another
            // peer's id included, and a file must not follow it there. The key of a peer that is not
            // connected falls through to the chat that is open.
            val matchedPeer = getMeshPeers(Unit).firstOrNull { it.id == peer }
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

/** What a line refused while the user's data is being wiped is answered with. */
const val DATA_BEING_CLEARED = "data is being cleared, try again"
