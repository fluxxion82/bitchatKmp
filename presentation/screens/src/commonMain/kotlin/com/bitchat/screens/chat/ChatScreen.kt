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
import com.bitchat.design.chat.channelCommandSuggestions
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
    peerNicknames: Map<String, String>
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
        state.pendingCommandFailure?.let { failure ->
            viewModel.postSystemMessage(failure.toMessage())
        }
    }

    val availableCommands = remember(state.selectedLocationChannel, state.currentChannel) {
        state.selectedLocationChannel.channelCommandSuggestions(state.currentChannel)
    }

    val commandSuggestions by remember(messageText.text, availableCommands) {
        derivedStateOf {
            val input = messageText.text
            if (!input.startsWith("/")) {
                emptyList()
            } else {
                val lowered = input.lowercase()
                availableCommands.filter { command ->
                    command.command.startsWith(lowered) ||
                            command.aliases.any { alias -> alias.startsWith(lowered) }
                }.sortedBy { it.command }
            }
        }
    }
    val isLocationChannel = !isPrivateChat && state.selectedLocationChannel is Channel.Location
    val dmMessages = selectedPrivatePeer?.let { dmState.privateChats[it] }.orEmpty()
    val recipientNickname = selectedPrivatePeer?.let { peerNicknames[it] ?: it.take(12) }

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
                    dmViewModel.sendMessage(recipientNickname ?: selectedPrivatePeer)
                } else {
                    viewModel.sendMessage()
                }
                messageText = TextFieldValue("")
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
