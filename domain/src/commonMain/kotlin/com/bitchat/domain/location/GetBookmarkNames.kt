package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.repository.LocationRepository

class GetBookmarkNames(
    private val locationRepository: LocationRepository
) : Usecase<Unit, Map<String, String>> {
    override suspend fun invoke(param: Unit): Map<String, String> {
        return locationRepository.getBookmarkNames()
    }
}
