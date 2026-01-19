package com.bitchat.domain.user.model

sealed class AppUser {
    data object Anonymous : AppUser()
    data class ActiveAnonymous(val name: String) : AppUser()
}
