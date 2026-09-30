package com.bitchat.tui.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bitchat.tui.LaunchedWork
import com.bitchat.viewmodel.location.LocationChannelsViewModel
import com.bitchat.viewmodel.location.LocationNotesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * A Locations view model for one visit to the Locations screen, in a store of its own, as the
 * Compose app scopes it to its sheet: clearing the store on [release] cancels the view model's
 * five-second location poll, which otherwise runs (and logs) for the whole process.
 *
 * Actions go through [act], which remembers the coroutines they start in the view model's scope:
 * [release] clears the store only once those have finished, so a channel selected or a teleport
 * made as the screen closes completes (it saves the new active chat) instead of being cancelled
 * with the store. Geohash sampling does not go through this view model (see
 * `TuiViewModels.sampleGeohashes`).
 */
internal class ScopedLocations(create: () -> LocationChannelsViewModel, private val scope: CoroutineScope) {
    private val store = ViewModelStore()
    val viewModel: LocationChannelsViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[LocationChannelsViewModel::class]
    private val work = LaunchedWork { viewModel.viewModelScope.coroutineContext.job }

    /** Runs [call] on the view model, keeping the store until what it started has finished. */
    fun act(call: LocationChannelsViewModel.() -> Unit) = work.track { viewModel.call() }

    fun release() {
        scope.launch {
            work.awaitAll()
            store.clear()
        }
    }
}

/**
 * A Location Notes view model for one visit, in a store of its own, for the same reason
 * [ScopedLocations] has one: its relay subscription lives only while the screen is up.
 */
internal class ScopedNotes(create: () -> LocationNotesViewModel, private val scope: CoroutineScope) {
    private val store = ViewModelStore()
    val viewModel: LocationNotesViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { create() } })[LocationNotesViewModel::class]
    private val work = LaunchedWork { viewModel.viewModelScope.coroutineContext.job }

    /** Runs [call] on the view model, keeping the store until what it started has finished. */
    fun act(call: LocationNotesViewModel.() -> Unit) = work.track { viewModel.call() }

    fun release() {
        scope.launch {
            work.awaitAll()
            store.clear()
        }
    }
}
