package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository

class SendNote(
    private val repository: LocationRepository,
    private val locationEventBus: LocationEventBus,
) : Usecase<SendNote.Params, Unit> {

    data class Params(
        val content: String,
        val nickname: String,
        val geohash: String
    )

    override suspend fun invoke(param: Params) {
        repository.sendNote(
            content = param.content,
            nickname = param.nickname,
            geohash = param.geohash
        )
        locationEventBus.update(LocationEvent.NotesChanged)
    }
}
