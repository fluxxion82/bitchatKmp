package com.bitchat.design.core

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_file
import bitchatkmp.presentation.design.generated.resources.progress_bar_brackets
import bitchatkmp.presentation.design.generated.resources.progress_empty
import bitchatkmp.presentation.design.generated.resources.progress_filled
import bitchatkmp.presentation.design.generated.resources.underscore
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

@Composable
fun FileSendingAnimation(
    modifier: Modifier = Modifier,
    fileName: String,
    progress: Float = 0f
) {
    var revealedChars by remember(fileName) { mutableFloatStateOf(0f) }
    var showCursor by remember { mutableStateOf(true) }

    val animatedChars by animateFloatAsState(
        targetValue = revealedChars,
        animationSpec = tween(
            durationMillis = 50 * fileName.length,
            easing = LinearEasing
        ),
        label = "fileNameReveal"
    )

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            showCursor = !showCursor
        }
    }

    LaunchedEffect(fileName) {
        revealedChars = fileName.length.toFloat()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = stringResource(Res.string.cd_file),
            tint = Color(0xFF00C851),
            modifier = Modifier.size(32.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                val revealedText = fileName.take(animatedChars.toInt())
                androidx.compose.material3.Text(
                    text = revealedText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = Color.White
                    ),
                    modifier = Modifier.padding(end = 2.dp)
                )

                if (animatedChars < fileName.length && showCursor) {
                    androidx.compose.material3.Text(
                        text = stringResource(Res.string.underscore),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Color.White
                        )
                    )
                }
            }

            FileProgressBars(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(20.dp)
            )
        }
    }
}

@Composable
private fun FileProgressBars(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val bars = 12
    val filledBars = (progress * bars).toInt()

    // Create a matrix-style progress bar string
    // val ctx = LocalContext.current
    val progressString = buildString {
        val brackets = stringResource(Res.string.progress_bar_brackets, "", 0)
        append("[")
        for (i in 0 until bars) {
            append(if (i < filledBars) stringResource(Res.string.progress_filled) else stringResource(Res.string.progress_empty))
        }
        append("] ")
        append("${(progress * 100).toInt()}%")
    }

    androidx.compose.material3.Text(
        text = progressString,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = Color(0xFF00FF7F)
        ),
        modifier = modifier
    )
}
