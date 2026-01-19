package com.bitchat.design.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_cancel
import bitchatkmp.presentation.design.generated.resources.file_unavailable
import bitchatkmp.presentation.design.generated.resources.status_delivered
import bitchatkmp.presentation.design.generated.resources.status_failed
import bitchatkmp.presentation.design.generated.resources.status_pending
import bitchatkmp.presentation.design.generated.resources.status_sending
import bitchatkmp.presentation.design.generated.resources.status_sent
import com.bitchat.design.core.FileSendingAnimation
import com.bitchat.design.media.AudioMessageItem
import com.bitchat.design.media.ImageMessageItem
import com.bitchat.design.util.formatMessageAsAnnotatedString
import com.bitchat.design.util.formatMessageHeaderAnnotatedString
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.DeliveryStatus
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import org.jetbrains.compose.resources.stringResource

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    myPeerID: String,
    modifier: Modifier = Modifier,
    forceScrollToBottom: Boolean = false,
    onTeleport: (GeohashChannel) -> Unit,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val listState = rememberLazyListState()

    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1

            val isFirstLoad = !hasScrolledToInitialPosition
            val isNearLatest = firstVisibleIndex <= 2

            if (isFirstLoad || isNearLatest) {
                listState.animateScrollToItem(0)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }

    val isAtLatest by remember {
        derivedStateOf {
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            firstVisibleIndex <= 2
        }
    }
    LaunchedEffect(isAtLatest) {
        onScrolledUpChanged?.invoke(!isAtLatest)
    }

    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
        reverseLayout = true
    ) {
        items(
            items = messages.asReversed(),
            key = { it.id }
        ) { message ->
            MessageItem(
                message = message,
                messages = messages,
                currentUserNickname = currentUserNickname,
                myPeerID = myPeerID,
                onTeleport = onTeleport,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onCancelTransfer = onCancelTransfer,
                onImageClick = onImageClick
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    messages: List<BitchatMessage> = emptyList(),
    onTeleport: (GeohashChannel) -> Unit,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                val endPad = if (message.isPrivate && message.sender == currentUserNickname) 16.dp else 0.dp
                MessageTextWithClickableNicknames(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    myPeerID = myPeerID,
                    onTeleport = onTeleport,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = endPad)
                )
            }

            if (message.isPrivate && message.sender == currentUserNickname) {
                message.deliveryStatus?.let { status ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp)
                    ) {
                        DeliveryStatusIcon(status = status)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageTextWithClickableNicknames(
    message: BitchatMessage,
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    myPeerID: String,
    onTeleport: (GeohashChannel) -> Unit,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    modifier: Modifier = Modifier
) {
    if (message.type == BitchatMessageType.Image) {
        ImageMessageItem(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            myPeerID = myPeerID,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            onImageClick = onImageClick,
            modifier = modifier
        )
        return
    }

    if (message.type == BitchatMessageType.Audio) {
        AudioMessageItem(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = myPeerID,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            modifier = modifier,
        )
        return
    }

    if (message.type == BitchatMessageType.File) {
        val (overrideProgress, _) = when (val st = message.deliveryStatus) {
            is DeliveryStatus.PartiallyDelivered -> {
                if (st.total > 0 && st.reached < st.total) {
                    (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5)
                } else null to null
            }

            else -> null to null
        }
        Column(modifier = modifier.fillMaxWidth()) {
            val headerText = formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                myPeerID = myPeerID,
            )
            val haptic = LocalHapticFeedback.current
            var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = headerText,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.pointerInput(message.id) {
                    detectTapGestures(onTap = { pos ->
                        val layout = headerLayout ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)
                        val ann = headerText.getStringAnnotations("nickname_click", offset, offset)
                        if (ann.isNotEmpty() && onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick.invoke(ann.first().item)
                        }
                    }, onLongPress = { onMessageLongPress?.invoke(message) })
                },
                onTextLayout = { headerLayout = it }
            )

            val packet = message.filePacket
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box {
                    if (packet != null) {
                        if (overrideProgress != null) {
                            FileSendingAnimation(
                                fileName = packet.fileName,
                                progress = overrideProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            FileMessageItem(
                                packet = packet,
                                onFileClick = {

                                }
                            )
                        }

                        val showCancel =
                            message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
                        if (showCancel) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                                    .clickable { onCancelTransfer?.invoke(message) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(Res.string.cd_cancel),
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    } else {
                        Text(text = stringResource(Res.string.file_unavailable), fontFamily = FontFamily.Monospace, color = Color.Gray)
                    }
                }
            }
        }
        return
    }

    val shouldAnimate = message.isMining
    if (shouldAnimate) {
        MessageWithMatrixAnimation(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = myPeerID,
            modifier = modifier
        )
    } else {
        val annotatedText = formatMessageAsAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = myPeerID,
        )

        val isSelf = message.senderPeerID == myPeerID ||
                message.sender == currentUserNickname ||
                message.sender.startsWith("$currentUserNickname#")

        val haptic = LocalHapticFeedback.current
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = annotatedText,
            modifier = modifier.pointerInput(message) {
                detectTapGestures(
                    onTap = { position ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        if (!isSelf && onNicknameClick != null) {
                            val nicknameAnnotations = annotatedText.getStringAnnotations(
                                tag = "nickname_click",
                                start = offset,
                                end = offset
                            )
                            if (nicknameAnnotations.isNotEmpty()) {
                                val nickname = nicknameAnnotations.first().item
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNicknameClick.invoke(nickname)
                                return@detectTapGestures
                            }
                        }
                        val geohashAnnotations = annotatedText.getStringAnnotations(
                            tag = "geohash_click",
                            start = offset,
                            end = offset
                        )
                        if (geohashAnnotations.isNotEmpty()) {
                            val geohash = geohashAnnotations.first().item
                            try {
                                val level = when (geohash.length) {
                                    in 0..2 -> GeohashChannelLevel.REGION
                                    in 3..4 -> GeohashChannelLevel.PROVINCE
                                    5 -> GeohashChannelLevel.CITY
                                    6 -> GeohashChannelLevel.NEIGHBORHOOD
                                    else -> GeohashChannelLevel.BLOCK
                                }
                                val channel = GeohashChannel(level, geohash.lowercase())
                                onTeleport(channel)
                            } catch (_: Exception) {
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        return@detectTapGestures
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            fontFamily = FontFamily.Monospace,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(
                color = MaterialTheme.colorScheme.onSurface
            ),
            onTextLayout = { result -> textLayoutResult = result }
        )
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme

    when (status) {
        is DeliveryStatus.Sending -> Text(
            text = stringResource(Res.string.status_sending),
            fontSize = 10.sp,
            color = colorScheme.primary.copy(alpha = 0.6f)
        )

        is DeliveryStatus.Sent -> Text(
            text = stringResource(Res.string.status_pending),
            fontSize = 10.sp,
            color = colorScheme.primary.copy(alpha = 0.6f)
        )

        is DeliveryStatus.Delivered -> Text(
            text = stringResource(Res.string.status_sent),
            fontSize = 10.sp,
            color = colorScheme.primary.copy(alpha = 0.8f)
        )

        is DeliveryStatus.Read -> Text(
            text = stringResource(Res.string.status_delivered),
            fontSize = 10.sp,
            color = Color(0xFF007AFF),
            fontWeight = FontWeight.Bold
        )

        is DeliveryStatus.Failed -> Text(
            text = stringResource(Res.string.status_failed),
            fontSize = 10.sp,
            color = Color.Red.copy(alpha = 0.8f)
        )

        is DeliveryStatus.PartiallyDelivered -> Text(
            text = stringResource(Res.string.status_sent),
            fontSize = 10.sp,
            color = colorScheme.primary.copy(alpha = 0.6f)
        )
    }
}
