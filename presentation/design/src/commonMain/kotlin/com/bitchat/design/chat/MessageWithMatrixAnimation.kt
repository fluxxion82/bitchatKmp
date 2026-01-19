package com.bitchat.design.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.bitchat.design.util.formatMessageAsAnnotatedString
import com.bitchat.domain.chat.model.BitchatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private enum class CharacterAnimationState {
    ENCRYPTED,
    DECRYPTING,
    FINAL,
}

@Composable
fun MessageWithMatrixAnimation(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    modifier: Modifier = Modifier
) {
    val isAnimating = message.isMining

    if (isAnimating) {
        AnimatedMessageDisplay(
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

        Text(
            text = annotatedText,
            modifier = modifier,
            fontFamily = FontFamily.Monospace,
            softWrap = true
        )
    }
}

@Composable
private fun AnimatedMessageDisplay(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
    modifier: Modifier = Modifier
) {
    var animatedContent by remember(message.content) { mutableStateOf(message.content) }
    val isAnimating = message.isMining

    var characterStates by remember(message.content) {
        mutableStateOf(message.content.map { char ->
            if (char == ' ') CharacterAnimationState.FINAL else CharacterAnimationState.ENCRYPTED
        })
    }

    LaunchedEffect(isAnimating, message.content) {
        if (isAnimating && message.content.isNotEmpty()) {
            val encryptedChars = "!@$%^&*()_+-=[]{}|;:,<>?".toCharArray()

            message.content.forEachIndexed { index, targetChar ->
                if (targetChar != ' ') {
                    launch {
                        delay(index * 50L)

                        while (true) {
                            while (characterStates.getOrNull(index) == CharacterAnimationState.ENCRYPTED) {
                                val newContent = animatedContent.toCharArray()
                                if (index < newContent.size) {
                                    newContent[index] = encryptedChars[Random.nextInt(encryptedChars.size)]
                                    animatedContent = newContent.concatToString()
                                }

                                delay(100L)

                                if (Random.nextFloat() < 0.1f) {
                                    val finalContent = animatedContent.toCharArray()
                                    if (index < finalContent.size) {
                                        finalContent[index] = targetChar
                                        animatedContent = finalContent.concatToString()
                                    }

                                    val finalStates = characterStates.toMutableList()
                                    finalStates[index] = CharacterAnimationState.FINAL
                                    characterStates = finalStates
                                    break
                                }
                            }

                            delay(2000L)

                            val resetStates = characterStates.toMutableList()
                            resetStates[index] = CharacterAnimationState.ENCRYPTED
                            characterStates = resetStates
                        }
                    }
                }
            }
        } else {
            animatedContent = message.content
            characterStates = message.content.map { CharacterAnimationState.FINAL }
        }
    }

    val animatedMessage = message.copy(content = animatedContent)

    val annotatedText = if (isAnimating) {
        formatMessageAsAnnotatedStringWithoutTimestamp(
            message = animatedMessage,
            currentUserNickname = currentUserNickname,
            myPeerID = myPeerID,
        )
    } else {
        formatMessageAsAnnotatedString(
            message = animatedMessage,
            currentUserNickname = currentUserNickname,
            myPeerID = myPeerID,
        )
    }

    Text(
        text = annotatedText,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        softWrap = true,
        overflow = androidx.compose.ui.text.style.TextOverflow.Visible,
        style = androidx.compose.ui.text.TextStyle(
            color = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun formatMessageAsAnnotatedStringWithoutTimestamp(
    message: BitchatMessage,
    currentUserNickname: String,
    myPeerID: String,
): AnnotatedString {
    val fullText = formatMessageAsAnnotatedString(
        message = message,
        currentUserNickname = currentUserNickname,
        myPeerID = myPeerID,
    )

    val text = fullText.text
    val timestampPattern = """ \[\d{2}:\d{2}:\d{2}].*$""".toRegex()
    val match = timestampPattern.find(text)

    return if (match != null) {
        val endIndex = match.range.first
        AnnotatedString(
            text = text.take(endIndex),
            spanStyles = fullText.spanStyles.filter { it.end <= endIndex },
            paragraphStyles = fullText.paragraphStyles.filter { it.end <= endIndex }
        )
    } else {
        fullText
    }
}
