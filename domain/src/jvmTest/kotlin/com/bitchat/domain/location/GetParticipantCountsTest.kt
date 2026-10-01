package com.bitchat.domain.location

import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.repository.LocationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class GetParticipantCountsTest {
    @Test
    fun countsAreReadWithoutAskingForAFix() = runTest {
        /*
         * The Locations sheet reads these whether or not location services are on, so this must
         * never take a fix: with them off, that would be a lookup the user has declined. The mocks
         * are strict -- any other repository call fails the test.
         */
        val locationRepository = mockk<LocationRepository>()
        val chatRepository = mockk<ChatRepository>()
        coEvery { locationRepository.getParticipantCounts() } returns mapOf("9q8yy" to 3)
        coEvery { chatRepository.getMeshPeers() } returns listOf(
            GeoPerson("a", "alice", Instant.fromEpochSeconds(0)),
            GeoPerson("b", "bob", Instant.fromEpochSeconds(0)),
        )

        val counts = GetParticipantCounts(locationRepository, chatRepository)(Unit)

        assertEquals(mapOf("9q8yy" to 3), counts.geohashCounts)
        assertEquals(2, counts.meshCount)
        coVerify(exactly = 0) { locationRepository.getAvailableGeohashChannels() }
        coVerify(exactly = 0) { locationRepository.getLocationGeohash(any()) }
    }
}
