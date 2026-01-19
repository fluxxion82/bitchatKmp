package com.bitchat.design.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_file
import com.bitchat.design.util.formatFileSize
import com.bitchat.domain.chat.model.BitchatFilePacket
import org.jetbrains.compose.resources.stringResource

@Composable
fun FileMessageItem(
    packet: BitchatFilePacket,
    onFileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth(0.8f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = stringResource(Res.string.cd_file),
                tint = getFileIconColor(packet.fileName),
                modifier = Modifier.size(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = packet.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatFileSize(packet.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FileTypeBadge(mimeType = packet.mimeType)
                }
            }
        }
    }
}

@Composable
private fun FileTypeBadge(mimeType: String) {
    val (text, color) = when {
        mimeType.startsWith("application/pdf") -> "PDF" to Color(0xFFDC2626)
        mimeType.startsWith("text/") -> "TXT" to Color(0xFF059669)
        mimeType.startsWith("image/") -> "IMG" to Color(0xFF7C3AED)
        mimeType.startsWith("audio/") -> "AUD" to Color(0xFFEA580C)
        mimeType.startsWith("video/") -> "VID" to Color(0xFF2563EB)
        mimeType.contains("document") -> "DOC" to Color(0xFF1D4ED8)
        mimeType.contains("zip") || mimeType.contains("rar") -> "ZIP" to Color(0xFF7C2D12)
        else -> "FILE" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold
    )
}

private fun getFileIconColor(fileName: String): Color {
    val extension = fileName.substringAfterLast(".", "").lowercase()
    return when (extension) {
        "pdf" -> Color(0xFFDC2626) // Red
        "doc", "docx" -> Color(0xFF1D4ED8)
        "xls", "xlsx" -> Color(0xFF059669)
        "ppt", "pptx" -> Color(0xFFEA580C)
        "txt", "json", "xml" -> Color(0xFF7C3AED)
        "jpg", "png", "gif", "webp" -> Color(0xFF2563EB)
        "mp3", "wav", "m4a" -> Color(0xFFEA580C)
        "mp4", "avi", "mov" -> Color(0xFFDC2626)
        "zip", "rar", "7z" -> Color(0xFF7C2D12)
        else -> Color(0xFF6B7280)
    }
}
