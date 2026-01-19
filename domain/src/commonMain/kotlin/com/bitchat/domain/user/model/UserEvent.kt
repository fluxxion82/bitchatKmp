package com.bitchat.domain.user.model

sealed class UserEvent {
    data class LoginChanged(val appUser: AppUser) : UserEvent()
    data object ProfileUpdated : UserEvent()
    data object StateChanged : UserEvent()
    data object NicknameUpdated : UserEvent()
    data class FavoriteStatusChanged(val peerID: String) : UserEvent()
}
