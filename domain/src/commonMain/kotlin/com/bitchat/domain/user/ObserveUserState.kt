package com.bitchat.domain.user

import com.bitchat.domain.app.model.UserState
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.base.invoke
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

class ObserveUserState(
    private val getUserState: GetUserState,
    private val userEventBus: UserEventBus,
) : Usecase<Unit, Flow<UserState>> {

    override suspend fun invoke(param: Unit): Flow<UserState> = channelFlow {
        // Subscribed before the first read: an event published during it must not be lost to a later one.
        val events = Channel<UserEvent>(Channel.BUFFERED)
        launch(start = CoroutineStart.UNDISPATCHED) { userEventBus.events().collect(events::send) }

        send(getUserState())
        for (event in events) {
            when (event) {
                UserEvent.StateChanged,
                is UserEvent.LoginChanged -> send(getUserState())

                else -> Unit
            }
        }
    }
}
