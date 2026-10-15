package com.bitchat.domain.chat.model

import com.bitchat.domain.location.model.Channel

/** The channel whose messages the chat follows. */
sealed interface ActiveChannel {
    val channel: Channel?

    /** A chat the user has open. */
    data class Chat(override val channel: Channel) : ActiveChannel

    /** Bluetooth is off: the mesh channel is still followed, for the messages that arrive over LoRa. */
    data object MeshForLoRa : ActiveChannel {
        override val channel: Channel = Channel.Mesh
    }

    /** No chat is open, or something blocks the app. */
    data object None : ActiveChannel {
        override val channel: Channel? = null
    }
}
