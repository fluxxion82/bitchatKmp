package com.bitchat.local.prefs

import com.bitchat.domain.tor.model.TorMode

interface TorPreferences {
    fun setTorMode(mode: TorMode)
    fun getTorMode(): TorMode
}
