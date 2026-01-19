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

@OptIn(ExperimentalCoroutinesApi::class)
class LocationNotesViewModel(
    private val observeNotes: ObserveNotes,
    private val sendNote: SendNote,
    private val getUserNickname: GetUserNickname,
    private val getLocationGeohash: GetLocationGeohash,
    private val resolveLocationName: ResolveLocationName,
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
                val geohash = getLocationGeohash(GeohashChannelLevel.BUILDING)
                val locationName = resolveLocationName(
                    ResolveLocationName.Params(geohash, GeohashChannelLevel.BUILDING)
                )

                _currentGeohash.value = geohash
                _state.update {
                    it.copy(geohash = geohash, locationName = locationName)
                }
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

    fun onSendNote() {
        val content = _state.value.inputText.trim()
        val geohash = _currentGeohash.value ?: return
        if (content.isEmpty()) return

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
    }
}
