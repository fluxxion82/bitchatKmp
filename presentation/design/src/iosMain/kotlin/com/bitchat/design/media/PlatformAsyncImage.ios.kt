package com.bitchat.design.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

@Composable
actual fun PlatformAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
    onState: (ImageLoadState) -> Unit
) {
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onState = { state ->
            val mappedState = when (state) {
                is AsyncImagePainter.State.Empty -> ImageLoadState.Empty
                is AsyncImagePainter.State.Loading -> ImageLoadState.Loading
                is AsyncImagePainter.State.Success -> ImageLoadState.Success(state.result.image)
                is AsyncImagePainter.State.Error -> ImageLoadState.Error(state.result.throwable)
            }
            onState(mappedState)
        }
    )
}
