package com.bitchat.domain.app

import com.bitchat.domain.app.repository.AppRepository
import com.bitchat.domain.base.Usecase
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
    private val userRepository: UserRepository,
    private val appRepository: AppRepository,
    private val locationRepository: LocationRepository,
    private val blockListRepository: BlockListRepository,
    private val nostrRepository: NostrRepository,
    private val torRepository: TorRepository,
    private val userEventBus: UserEventBus,
) : Usecase<Unit, Unit> {

    override suspend fun invoke(param: Unit) {
        chatRepository.clearData()
        userRepository.clearData()
        appRepository.clearData()
        locationRepository.clearData()
        blockListRepository.clearData()
        nostrRepository.clearData()
        torRepository.clearData()
        userEventBus.update(UserEvent.StateChanged)
    }
}
