package com.bitchat.domain.chat

import com.bitchat.domain.app.model.ActiveState
import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.base.invoke
import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ActiveChannel
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.user.GetUserState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ObserveActiveChannel(
    private val getUserState: GetUserState,
    private val chatEventBus: ChatEventBus,
) : Usecase<Unit, Flow<ActiveChannel>> {

    override suspend fun invoke(param: Unit): Flow<ActiveChannel> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = Channel<ChatEvent>(Channel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { chatEventBus.events().collect(events::send) }

        send(activeChannel())
        // Every ChannelChanged is read, even two in a row: SaveUserStateAction announces a switch
        // before it saves the new state and again after, and the first read can see the old one.
        for (event in events) {
            when (event) {
                ChatEvent.ChannelChanged -> send(activeChannel())
                else -> Unit
            }
        }
    }

    private suspend fun activeChannel(): ActiveChannel = when (val userState = getUserState()) {
        is UserState.Active -> (userState.activeState as? ActiveState.Chat)
            ?.let { ActiveChannel.Chat(it.channel) }
            ?: ActiveChannel.None
        UserState.BluetoothDisabled -> ActiveChannel.MeshForLoRa
        else -> ActiveChannel.None
    }
}
