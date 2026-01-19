package com.bitchat.domain.location.model

sealed class LocationEvent {
    data object ChannelChanged : LocationEvent()
    data object BookmarksChanged : LocationEvent()
    data object NotesChanged : LocationEvent()
    data object PermissionStateChanged : LocationEvent()
    data object LocationServicesChanged : LocationEvent()
    data object ParticipantsChanged : LocationEvent()
}
