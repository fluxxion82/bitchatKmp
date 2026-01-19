package com.bitchat.design.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.send_message
import bitchatkmp.presentation.design.generated.resources.type_a_message_placeholder
import com.bitchat.design.chat.media.VoiceRecordButton
import com.bitchat.design.imagepicker.SelectionMode
import com.bitchat.design.imagepicker.rememberImagePickerLauncher
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun MessageInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    showMediaButtons: Boolean,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val isFocused = remember { mutableStateOf(false) }
    val hasText = value.text.isNotBlank() // Check if there's text for send button state
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var amplitude by remember { mutableStateOf(0) }

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp), // Reduced padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.weight(1f)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(if (isRecording) Color.Transparent else colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (hasText) onSend()
                }),
                visualTransformation = CombinedVisualTransformation(
                    listOf(SlashCommandVisualTransformation(), MentionVisualTransformation())
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        isFocused.value = focusState.isFocused
                    }
            )

            if (value.text.isEmpty() && !isRecording) {
                Text(
                    text = stringResource(Res.string.type_a_message_placeholder),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = colorScheme.onSurface.copy(alpha = 0.5f), // Muted grey
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (value.text.isEmpty() && showMediaButtons) {
            val bg =
                if (colorScheme.background == Color.Black) Color(0xFF00FF00).copy(alpha = 0.75f) else Color(0xFF008000).copy(alpha = 0.75f)

            val latestSelectedPeer = rememberUpdatedState(selectedPrivatePeer)
            val latestChannel = rememberUpdatedState(currentChannel)
            val latestOnSendVoiceNote = rememberUpdatedState(onSendVoiceNote)
            val latestOnSendImageNote = rememberUpdatedState(onSendImageNote)

            val scope = rememberCoroutineScope()

            if (!isRecording) {
                val imagePicker = rememberImagePickerLauncher(
                    selectionMode = SelectionMode.Single,
                    scope = scope,
                    onResult = { mediaDataList ->
                        mediaDataList.firstOrNull()?.let { mediaData ->
                            latestOnSendImageNote.value(
                                latestSelectedPeer.value,
                                latestChannel.value,
                                mediaData.mediaUrl
                            )
                        }
                    }
                )

                IconButton(
                    onClick = { scope.launch { imagePicker.launch() } },
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .background(
                                color = colorScheme.onSurface.copy(alpha = 0.2f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Add image",
                            modifier = Modifier.size(20.dp),
                            tint = colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            VoiceRecordButton(
                backgroundColor = bg,
                onRecordingStarted = {
                    isRecording = true
                    elapsedMs = 0L
                    if (isFocused.value) {
                        try { focusRequester.requestFocus() } catch (_: Exception) {}
                    }
                },
                onRecordingAmplitude = { amp, ms ->
                    amplitude = amp
                    elapsedMs = ms
                },
                onRecordingFinished = { path ->
                    isRecording = false
                    latestOnSendVoiceNote.value(
                        latestSelectedPeer.value,
                        latestChannel.value,
                        path
                    )
                },
                onRecordingCancelled = {
                    isRecording = false
                }
            )
        } else {
            IconButton(
                onClick = { if (hasText) onSend() },
                enabled = hasText,
                modifier = Modifier.size(32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            color = if (!hasText) {
                                colorScheme.onSurface.copy(alpha = 0.3f)
                            } else if (selectedPrivatePeer != null || currentChannel != null) {
                                Color(0xFFFF9500).copy(alpha = 0.75f)
                            } else if (colorScheme.background == Color.Black) {
                                Color(0xFF00FF00).copy(alpha = 0.75f)
                            } else {
                                Color(0xFF008000).copy(alpha = 0.75f)
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(Res.string.send_message),
                        modifier = Modifier.size(20.dp),
                        tint = if (!hasText) {
                            colorScheme.onSurface.copy(alpha = 0.5f)
                        } else if (selectedPrivatePeer != null || currentChannel != null) {
                            Color.Black
                        } else if (colorScheme.background == Color.Black) {
                            Color.Black
                        } else {
                            Color.White
                        }
                    )
                }
            }
        }
    }
}
