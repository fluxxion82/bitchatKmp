package com.bitchat.screens

enum class Platform {
    ANDROID, IOS, DESKTOP
}

expect fun getPlatform(): Platform
