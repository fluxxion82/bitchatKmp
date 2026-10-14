package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.domain.location.repository.LocationRepository

class SetSavedPlace(
    private val locationRepository: LocationRepository,
) : Usecase<SavedPlace, Unit> {
    override suspend fun invoke(param: SavedPlace) = locationRepository.setSavedPlace(param)
}
