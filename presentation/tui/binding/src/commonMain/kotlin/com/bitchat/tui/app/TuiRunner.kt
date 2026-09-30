package com.bitchat.tui.app

import androidx.compose.runtime.CompositionLocalProvider
import com.bitchat.tui.LocalConsoleSafe
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.RenderMode
import com.jakewharton.mosaic.runMosaic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Initializes the app and runs its terminal composition, returning false without a controlling terminal. */
suspend fun runBitchatTui(
    initialize: suspend () -> Unit,
    viewModels: () -> TuiViewModels,
    consoleSafe: Boolean,
    background: CoroutineScope,
    notice: StateFlow<String?>,
): Boolean {
    initialize()
    val models = viewModels()
    return runMosaic(onNonInteractive = NonInteractivePolicy.Return, renderMode = RenderMode.FullScreen) {
        CompositionLocalProvider(LocalConsoleSafe provides consoleSafe) {
            BitchatTui(models, background, notice)
        }
    }
}
