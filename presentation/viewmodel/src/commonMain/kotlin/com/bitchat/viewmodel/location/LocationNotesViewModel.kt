package com.bitchat.viewmodel.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.domain.location.GetLocationGeohash
import com.bitchat.domain.location.ObserveNotes
import com.bitchat.domain.location.ResolveLocationName
import com.bitchat.domain.location.SendNote
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.user.GetUserNickname
import com.bitchat.viewvo.location.LocationNotesState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The notes left at one place.
 *
 * [geohash] names that place. Left out, it is the building the device is standing in, which is
 * what the Compose apps show and needs a location fix at [GeohashChannelLevel.BUILDING]; a host
 * with no location source at all (the Pi) never gets one, so it passes the channel the user has
 * joined instead and the notes are that channel's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationNotesViewModel(
    private val observeNotes: ObserveNotes,
    private val sendNote: SendNote,
    private val getUserNickname: GetUserNickname,
    private val getLocationGeohash: GetLocationGeohash,
    private val resolveLocationName: ResolveLocationName,
    private val geohash: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(LocationNotesState())
    val state: StateFlow<LocationNotesState> = _state.asStateFlow()

    private val _currentGeohash = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            _currentGeohash
                .filterNotNull()
                .flatMapLatest { geohash ->
                    observeNotes(geohash)
                }
                .collect { notes ->
                    _state.update { it.copy(notes = notes, isLoading = false) }
                }
        }

        loadLocationGeohash()
    }

    private fun loadLocationGeohash() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val level = if (geohash == null) GeohashChannelLevel.BUILDING else geohashLevel(geohash)
                val geohash = geohash ?: getLocationGeohash(GeohashChannelLevel.BUILDING)
                // The place is known now: start listening, and take notes, before asking a
                // geocoder what it is called. A note typed meanwhile is not refused for nothing.
                _currentGeohash.value = geohash
                _state.update { it.copy(geohash = geohash) }
                _state.update { it.copy(locationName = resolveLocationName(ResolveLocationName.Params(geohash, level))) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = "Could not get location: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun onInputTextChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /**
     * Posts what has been typed, and answers whether it was taken. A caller clears its editor only
     * when it was: the place is resolved in the background, so a note typed in the first moments
     * would otherwise be dropped between the prompt and here.
     */
    fun onSendNote(): Boolean {
        val content = _state.value.inputText.trim()
        if (content.isEmpty()) return false
        val geohash = _currentGeohash.value ?: run {
            _state.update { it.copy(errorMessage = NOT_READY_YET) }
            return false
        }

        viewModelScope.launch {
            _state.update { it.copy(isSending = true) }
            try {
                val nickname = getUserNickname(Unit).first()
                sendNote(
                    SendNote.Params(
                        content = content,
                        nickname = nickname,
                        geohash = geohash
                    )
                )
                _state.update { it.copy(inputText = "", isSending = false) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        errorMessage = e.message ?: "Failed to send note",
                        isSending = false
                    )
                }
            }
        }
        return true
    }
}

/** What a note refused before the place is known is answered with. */
const val NOT_READY_YET = "still finding this place, try again"

/** The level a geohash of this length names, for looking its place name up. */
private fun geohashLevel(geohash: String): GeohashChannelLevel =
    GeohashChannelLevel.entries.minByOrNull { kotlin.math.abs(it.precision - geohash.length) }
        ?: GeohashChannelLevel.BUILDING
