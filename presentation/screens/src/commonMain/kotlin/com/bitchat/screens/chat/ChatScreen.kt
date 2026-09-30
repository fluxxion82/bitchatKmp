package com.bitchat.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.bitchat.design.chat.ChatContent
import com.bitchat.viewvo.chat.channelCommandSuggestions
import com.bitchat.viewvo.chat.matching
import com.bitchat.design.mapper.toMessage
import com.bitchat.design.util.buildMentionInsertionText
import com.bitchat.domain.location.model.Channel
import com.bitchat.viewmodel.chat.ChatViewModel
import com.bitchat.viewmodel.chat.DmViewModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    dmViewModel: DmViewModel,
    selectedPrivatePeer: String?,
    selectedChannel: Channel? = null,
) {
    val state by viewModel.state.collectAsState()
    val dmState by dmViewModel.state.collectAsState()
    val isPrivateChat = selectedPrivatePeer != null
    val activeInput = if (isPrivateChat) dmState.messageInput else state.messageInput
    var messageText by remember { mutableStateOf(TextFieldValue(activeInput)) }

    LaunchedEffect(activeInput) {
        if (activeInput != messageText.text) {
            messageText = TextFieldValue(
                text = activeInput,
                selection = TextRange(activeInput.length)
            )
        }
    }

    LaunchedEffect(selectedPrivatePeer) {
        if (selectedPrivatePeer == null) {
            messageText = TextFieldValue("")
            dmViewModel.updateMessageInput("")
            viewModel.updateMessageInput("")
        }
    }

    LaunchedEffect(state.pendingCommandFailure) {
        state.pendingCommandFailure?.let { pending ->
            viewModel.postCommandFailure(pending, pending.failure.toMessage())
        }
    }

    val availableCommands = remember(state.selectedLocationChannel, state.currentChannel) {
        state.selectedLocationChannel.channelCommandSuggestions(state.currentChannel)
    }

    val commandSuggestions by remember(messageText.text, availableCommands) {
        derivedStateOf {
            availableCommands.matching(messageText.text)
        }
    }
    val isLocationChannel = !isPrivateChat && state.selectedLocationChannel is Channel.Location
    val dmMessages = selectedPrivatePeer?.let { dmState.privateChats[it] }.orEmpty()
    // The DM on screen, sent to directly: the active chat is read later, in another coroutine, and
    // may by then be a public channel.
    val dmChannel = privateChannelFor(selectedChannel, selectedPrivatePeer)

    ChatContent(
        messages = if (isPrivateChat) dmMessages else state.messages,
        nickname = state.nickname,
        messageText = messageText,
        onMessageTextChange = { updated ->
            messageText = updated
            if (isPrivateChat) {
                dmViewModel.updateMessageInput(updated.text)
            } else {
                viewModel.updateMessageInput(updated.text)
            }
        },
        onSendMessage = {
            if (messageText.text.trim().isNotEmpty()) {
                if (isPrivateChat) {
                    // The DM on screen, captured now. A LoRa node DM or a header caught mid-change
                    // is refused at once, with an error, and the draft stays.
                    if (dmViewModel.sendTo(dmChannel, messageText.text)) {
                        dmViewModel.updateMessageInput("")
                        messageText = TextFieldValue("")
                    }
                } else {
                    // Refused (the user's data is being wiped): the draft stays, with an error.
                    if (viewModel.sendMessage()) {
                        messageText = TextFieldValue("")
                    }
                }
            }
        },
        onSendVoiceNote = { peer, channel, filePath ->
            viewModel.sendVoiceNote(peer, channel, filePath)
        },
        onSendImageNote = { peer, channel, filePath ->
            viewModel.sendImageNote(peer, channel, filePath)
        },
        isSending = if (isPrivateChat) dmState.isSending else state.isSending,
        isLoading = state.isLoading,
        errorMessage = if (isPrivateChat) dmState.errorMessage else state.errorMessage,
        onClearError = if (isPrivateChat) dmViewModel::clearError else viewModel::clearError,
        selectedChannel = state.selectedLocationChannel,
        currentChannel = state.currentChannel,
        showCommandSuggestions = commandSuggestions.isNotEmpty(),
        commandSuggestions = commandSuggestions,
        onCommandSuggestionClick = { suggestion ->
            val newText = "${suggestion.command} "
            messageText = TextFieldValue(newText, selection = TextRange(newText.length))
            if (isPrivateChat) {
                dmViewModel.updateMessageInput(newText)
            } else {
                viewModel.updateMessageInput(newText)
            }
        },
        onNicknameClick = { fullSenderName ->
            val newText = buildMentionInsertionText(
                currentText = messageText.text,
                fullSenderName = fullSenderName,
                isLocationChannel = isLocationChannel
            )
            messageText = TextFieldValue(newText, selection = TextRange(newText.length))
            if (isPrivateChat) {
                dmViewModel.updateMessageInput(newText)
            } else {
                viewModel.updateMessageInput(newText)
            }
        },
        selectedPrivatePeer = selectedPrivatePeer
    )
}

/**
 * [selectedChannel] when it is the private chat with [privatePeer] (a mesh or Nostr DM, or a
 * Meshtastic node, which the view model refuses), else null (refused as well).
 */
internal fun privateChannelFor(selectedChannel: Channel?, privatePeer: String?): Channel? = when {
    privatePeer == null -> null
    selectedChannel is Channel.MeshDM && selectedChannel.peerID == privatePeer -> selectedChannel
    selectedChannel is Channel.NostrDM && selectedChannel.peerID == privatePeer -> selectedChannel
    selectedChannel is Channel.Meshtastic && selectedChannel.nodeNum?.toString(16)?.padStart(8, '0') == privatePeer -> selectedChannel
    else -> null
}
