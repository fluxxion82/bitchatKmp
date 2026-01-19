package com.bitchat.viewvo.location

import com.bitchat.domain.location.model.Note

data class LocationNotesState(
    val notes: List<Note> = emptyList(),
    val geohash: String = "",
    val locationName: String? = null,
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null
)
