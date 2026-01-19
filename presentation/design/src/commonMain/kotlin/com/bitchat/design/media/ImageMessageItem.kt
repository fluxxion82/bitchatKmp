package com.bitchat.design.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_cancel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.bitchat.design.util.formatMessageHeaderAnnotatedString
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.DeliveryStatus
import org.jetbrains.compose.resources.stringResource

@Composable
fun ImageMessageItem(
    message: BitchatMessage,
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    myPeerID: String,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val rawPath = message.content.trim()
    val path = if (rawPath.startsWith("/") && !rawPath.startsWith("file://")) {
        "file://$rawPath"
    } else {
        rawPath
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

        // Collect all image paths from messages for swipe navigation
        // Convert absolute file paths to file:// URIs for consistency
        val imagePaths = remember(messages) {
            messages.filter { it.type == BitchatMessageType.Image }
                .map { msg ->
                    val raw = msg.content.trim()
                    if (raw.startsWith("/") && !raw.startsWith("file://")) {
                        "file://$raw"
                    } else {
                        raw
                    }
                }
        }

        Box {
            var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
            AsyncImage(
                model = path,
                contentDescription = "Image message",
                modifier = Modifier
                    .defaultMinSize(minWidth = 100.dp, minHeight = 100.dp)
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        val currentIndex = imagePaths.indexOf(path)
                        onImageClick?.invoke(path, imagePaths, currentIndex)
                    },
                contentScale = ContentScale.Fit,
                onState = { state ->
                    val messageId = message.id
                    when (state) {
                        is AsyncImagePainter.State.Loading -> println("ImageMessageItem: LOADING messageId=$messageId")
                        is AsyncImagePainter.State.Success -> {
                            val image = state.result.image
                            println("ImageMessageItem: SUCCESS messageId=$messageId image=$image")
                        }
                        is AsyncImagePainter.State.Error ->
                            println("ImageMessageItem: ERROR messageId=$messageId error=${state.result.throwable}")
                        is AsyncImagePainter.State.Empty -> println("ImageMessageItem: EMPTY messageId=$messageId")
                    }
                    imageState = state
                }
            )

            if (imageState is AsyncImagePainter.State.Loading) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .size(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (imageState is AsyncImagePainter.State.Error) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .size(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = "Image unavailable",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "Image unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            if (imageState is AsyncImagePainter.State.Empty) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .size(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Image",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
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
        }
    }
}
