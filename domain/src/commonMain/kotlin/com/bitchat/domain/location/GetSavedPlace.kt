package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.domain.location.repository.LocationRepository

class GetSavedPlace(
    private val locationRepository: LocationRepository,
) : Usecase<Unit, SavedPlace?> {
    override suspend fun invoke(param: Unit): SavedPlace? = locationRepository.getSavedPlace()
}
