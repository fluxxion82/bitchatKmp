package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.eventbus.LocationEventBus
import com.bitchat.domain.location.model.LocationEvent
import com.bitchat.domain.location.repository.LocationRepository

class ToggleBookmark(
    private val locationRepository: LocationRepository,
    private val locationEventBus: LocationEventBus,
) : Usecase<String, Unit> {
    override suspend fun invoke(param: String) {
        locationRepository.toggleBookmark(param)
        locationEventBus.update(LocationEvent.BookmarksChanged)
    }
}
