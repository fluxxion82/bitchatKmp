package com.bitchat.tui.app

import com.bitchat.domain.location.BeginGeohashSampling
import com.bitchat.domain.location.EndGeohashSampling
import com.bitchat.viewmodel.chat.ChatViewModel
import com.bitchat.viewmodel.chat.DmViewModel
import com.bitchat.viewmodel.location.LocationChannelsViewModel
import com.bitchat.viewmodel.location.LocationNotesViewModel
import com.bitchat.viewmodel.main.MainViewModel
import com.bitchat.viewmodel.settings.SettingsViewModel
import org.koin.core.Koin
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

/** The process-lifetime view models used by the terminal UI. */
class TuiViewModels(
    val main: MainViewModel,
    val chat: ChatViewModel,
    val dm: DmViewModel,
    val settings: SettingsViewModel,
    val newLocations: () -> LocationChannelsViewModel,
    val newNotes: (String) -> LocationNotesViewModel,
    val sampleGeohashes: suspend (List<String>) -> Unit,
)

/** Creates the view models after application initialisation; factories deliberately live for the process lifetime. */
fun Koin.tuiViewModels() = TuiViewModels(
    main = get(),
    chat = get(),
    dm = get(),
    settings = get { parametersOf(false) },
    newLocations = { get() },
    newNotes = { geohash -> get { parametersOf(geohash) } },
    sampleGeohashes = { geohashes ->
        if (geohashes.isEmpty()) get<EndGeohashSampling>()(Unit) else get<BeginGeohashSampling>()(geohashes)
    },
)
