package com.bitchat.domain.location

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.location.repository.LocationRepository

class GetBookmarkedChannels(
    private val locationRepository: LocationRepository
) : Usecase<Unit, List<String>> {
    override suspend fun invoke(param: Unit): List<String> {
        return locationRepository.getBookmarkedGeohashes()
    }
}
