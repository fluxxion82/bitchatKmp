package com.bitchat.design.location

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.domain.location.model.Note
import com.bitchat.viewvo.location.LocationNotesState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationNotesContent(
    state: LocationNotesState,
    onInputTextChange: (String) -> Unit,
    onSendNote: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val accentGreen = if (isDark) Color.Green else Color(0xFF008000)

    val listState = rememberLazyListState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            LocationNotesHeader(
                geohash = state.geohash,
                count = state.notes.size,
                locationName = state.locationName,
                accentGreen = accentGreen,
                backgroundColor = backgroundColor,
                onClose = onDismiss
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(backgroundColor)
            ) {
                if (state.isLoading) {
                    LoadingRow()
                } else if (state.notes.isEmpty()) {
                    EmptyRow()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        items(state.notes, key = { it.id }) { note ->
                            NoteRow(note = note)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                thickness = 1.dp
            )

            LocationNotesInputSection(
                inputText = state.inputText,
                onInputTextChange = onInputTextChange,
                sendButtonEnabled = state.inputText.trim().isNotEmpty() && !state.isSending,
                accentGreen = accentGreen,
                backgroundColor = backgroundColor,
                onSend = onSendNote
            )
        }
    }
}

@Composable
private fun LocationNotesHeader(
    geohash: String,
    count: Int,
    locationName: String?,
    accentGreen: Color,
    backgroundColor: Color,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$geohash ± • $count note${if (count != 1) "s" else ""}",
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✕",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        locationName?.let { name ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = name,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun NoteRow(note: Note) {
    val baseName = note.displayName.split("#", limit = 2).firstOrNull() ?: note.displayName
    val timestamp = formatTimestamp(note.createdAt)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "@$baseName",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (timestamp.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = timestamp,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = note.content,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Loading location notes...",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun EmptyRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "No notes yet",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Be the first to leave a note at this location",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun LocationNotesInputSection(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    sendButtonEnabled: Boolean,
    accentGreen: Color,
    backgroundColor: Color,
    onSend: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )

            if (inputText.isEmpty()) {
                Text(
                    text = "Write a location note...",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = { if (sendButtonEnabled) onSend() },
            enabled = sendButtonEnabled,
            modifier = Modifier.size(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = if (sendButtonEnabled) accentGreen else colorScheme.onSurface.copy(alpha = 0.3f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp),
                    tint = if (sendButtonEnabled) Color.White else colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun formatTimestamp(createdAt: Int): String {
    val nowSeconds = kotlin.time.Clock.System.now().epochSeconds
    val diffSeconds = nowSeconds - createdAt
    val diffDays = diffSeconds / 86400

    return when {
        diffSeconds < 60 -> ""
        diffSeconds < 3600 -> {
            val minutes = (diffSeconds / 60).toInt()
            "${minutes}m ago"
        }

        diffSeconds < 86400 -> {
            val hours = (diffSeconds / 3600).toInt()
            "${hours}h ago"
        }

        diffDays < 7 -> {
            "${diffDays.toInt()}d ago"
        }

        else -> {
            // Format as MM/DD/YY - simple date formatting without kotlinx.datetime
            // For now just show days ago for older notes
            // TODO: Implement proper date formatting when needed
            "${diffDays.toInt()}d ago"
        }
    }
}
