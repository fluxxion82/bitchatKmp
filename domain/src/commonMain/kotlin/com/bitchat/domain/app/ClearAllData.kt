package com.bitchat.domain.app

import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.ChatNotices
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.repository.LocationRepository
import com.bitchat.domain.nostr.repository.NostrRepository
import com.bitchat.domain.tor.repository.TorRepository
import com.bitchat.domain.user.eventbus.UserEventBus
import com.bitchat.domain.user.model.UserEvent
import com.bitchat.domain.user.repository.BlockListRepository
import com.bitchat.domain.user.repository.UserRepository

class ClearAllData(
    private val chatRepository: ChatRepository,
    private val chatNotices: ChatNotices,
    private val userRepository: UserRepository,
    private val appRepository: AppRepository,
    private val locationRepository: LocationRepository,
    private val blockListRepository: BlockListRepository,
    private val nostrRepository: NostrRepository,
    private val torRepository: TorRepository,
    private val userEventBus: UserEventBus,
) : Usecase<Unit, Unit> {

    /**
     * Clears every store the user's identity is in. The whole of it runs inside
     * [ChatNotices.reset], which is the barrier: a command already running is invalidated before
     * the first store is touched, no command may start while the stores are half cleared, and the
     * lines the app had shown (they name peers and channels) go with the rest.
     */
    override suspend fun invoke(param: Unit) {
        chatNotices.reset {
            chatRepository.clearData()
            userRepository.clearData()
            appRepository.clearData()
            locationRepository.clearData()
            blockListRepository.clearData()
            nostrRepository.clearData()
            torRepository.clearData()
        }
        userEventBus.update(UserEvent.StateChanged)
    }
}
