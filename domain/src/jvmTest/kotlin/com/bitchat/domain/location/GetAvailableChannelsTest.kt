package com.bitchat.domain.location

import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.LocationFixInfo
import com.bitchat.domain.location.model.LocationUnavailableException
import com.bitchat.domain.location.model.NearbyChannels
import com.bitchat.domain.location.repository.LocationRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GetAvailableChannelsTest {
    private val city = GeohashChannel(GeohashChannelLevel.CITY, "9q8yy")
    private val building = GeohashChannel(GeohashChannelLevel.BUILDING, "9q8yymhz")

    @Test
    fun buildingsAreLeftOutAndTheFixStaysWithTheChannels() = runTest {
        val fix = LocationFixInfo(
            LocationFixInfo.Source.DEFAULT,
            ageMillis = 0,
            unavailableReason = LocationUnavailableException.Reason.NO_SOURCE,
        )
        val repository = mockk<LocationRepository>()
        coEvery { repository.getAvailableGeohashChannels() } returns NearbyChannels(listOf(city, building), fix)

        assertEquals(NearbyChannels(listOf(city), fix), GetAvailableChannels(repository)(Unit))
    }

    @Test
    fun noFixInfoStaysNone() = runTest {
        val repository = mockk<LocationRepository>()
        coEvery { repository.getAvailableGeohashChannels() } returns NearbyChannels(listOf(building), null)

        assertEquals(NearbyChannels(emptyList(), null), GetAvailableChannels(repository)(Unit))
    }
}
