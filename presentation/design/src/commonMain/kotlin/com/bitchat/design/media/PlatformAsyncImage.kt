package com.bitchat.design.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Image loading state for cross-platform async image loading.
 */
sealed interface ImageLoadState {
    data object Empty : ImageLoadState
    data object Loading : ImageLoadState
    data class Success(val image: Any?) : ImageLoadState
    data class Error(val throwable: Throwable?) : ImageLoadState
}

/**
 * Platform-specific async image composable.
 * On platforms with Coil support (Android, iOS, Desktop), uses Coil's AsyncImage.
 * On linuxArm64, shows a placeholder as image loading is not supported.
 */
@Composable
expect fun PlatformAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    onState: (ImageLoadState) -> Unit
)
