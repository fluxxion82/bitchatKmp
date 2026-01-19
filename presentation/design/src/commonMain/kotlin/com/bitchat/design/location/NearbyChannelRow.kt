package com.bitchat.design.location

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import bitchatkmp.presentation.design.generated.resources.Res
import bitchatkmp.presentation.design.generated.resources.cd_add_bookmark
import bitchatkmp.presentation.design.generated.resources.cd_remove_bookmark
import com.bitchat.domain.location.model.GeohashChannel
import org.jetbrains.compose.resources.stringResource
import kotlin.math.pow
import kotlin.math.round

@Composable
fun NearbyChannelRow(
    channel: GeohashChannel,
    participantCount: Int,
    locationName: String?,
    isSelected: Boolean,
    isBookmarked: Boolean,
    onSelect: () -> Unit,
    onToggleBookmark: () -> Unit,
    titleOverride: String? = null,
    titleColor: Color? = Color(0xFF32D74B)
) {
    val title = titleOverride ?: geohashTitleWithCount(channel.level, participantCount)
    val coverage = coverageString(channel.geohash.length)
    val subtitle = buildString {
        append("#${channel.geohash} • $coverage")
        if (locationName != null) {
            append(" • ~$locationName")
        }
    }

    ChannelRow(
        title = title,
        subtitle = subtitle,
        isSelected = isSelected,
        titleColor = titleColor,
        titleBold = participantCount > 0,
        trailingContent = {
            IconButton(onClick = onToggleBookmark, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (isBookmarked) {
                        stringResource(Res.string.cd_remove_bookmark)
                    } else {
                        stringResource(Res.string.cd_add_bookmark)
                    },
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        },
        onClick = onSelect
    )
}

private fun coverageString(precision: Int): String {
    val maxMeters = when (precision) {
        2 -> 1_250_000.0
        3 -> 156_000.0
        4 -> 39_100.0
        5 -> 4_890.0
        6 -> 1_220.0
        7 -> 153.0
        8 -> 38.2
        9 -> 4.77
        10 -> 1.19
        else -> if (precision <= 1) 5_000_000.0 else 1.19 * 0.25.pow((precision - 10).toDouble())
    }

    val km = maxMeters / 1000.0
    return "~${formatDistance(km)} km"
}

private fun formatDistance(value: Double): String {
    return when {
        value >= 100 -> value.toInt().toString()
        value >= 10 -> {
            val rounded = round(value * 10) / 10
            rounded.toString()
        }

        else -> {
            val rounded = round(value * 10) / 10
            rounded.toString()
        }
    }
}
