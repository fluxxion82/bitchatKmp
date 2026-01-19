package com.bitchat.design.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.location.model.Channel
import com.bitchat.viewvo.chat.CommandSuggestion

@Composable
fun ChatContent(
    messages: List<BitchatMessage>,
    nickname: String,
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSendMessage: () -> Unit,
    onSendVoiceNote: (peer: String?, channel: String?, filePath: String) -> Unit,
    onSendImageNote: (peer: String?, channel: String?, filePath: String) -> Unit,
    isSending: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    selectedChannel: Channel,
    currentChannel: String?,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onNicknameClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                MessagesList(
                    messages = messages,
                    currentUserNickname = nickname,
                    myPeerID = nickname,
                    onTeleport = { },
                    onNicknameClick = onNicknameClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            ChatInputSection(
                messageText = messageText,
                onMessageTextChange = onMessageTextChange,
                onSend = onSendMessage,
                onSendVoiceNote = onSendVoiceNote,
                onSendImageNote = onSendImageNote,
                onSendFileNote = { _, _, _ -> },
                showCommandSuggestions = showCommandSuggestions,
                commandSuggestions = commandSuggestions,
                showMentionSuggestions = false,
                mentionSuggestions = emptyList(),
                onCommandSuggestionClick = onCommandSuggestionClick,
                onMentionSuggestionClick = { },
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                showMediaButtons = selectedChannel == Channel.Mesh
            )
        }

        if (errorMessage != null) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = onClearError) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text(errorMessage)
            }
        }
    }
}
