package com.bitchat.repo.location

import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.local.service.GeocoderService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookmarkNameResolverTest {
    @Test
    fun compositeNameForCandidatesReturnsFirstTwo() {
        val result = compositeNameForCandidates(listOf("Alpha", "Beta", "Gamma"))

        assertEquals("Alpha and Beta", result)
    }

    @Test
    fun compositeNameForCandidatesReturnsNullForEmpty() {
        val result = compositeNameForCandidates(emptyList())

        assertNull(result)
    }

    @Test
    fun resolveBookmarkNameFallsBackByLength() = runTest {
        val geocoder = object : GeocoderService {
            override suspend fun reverseGeocode(
                lat: Double,
                lon: Double,
                level: GeohashChannelLevel
            ): String {
                return when (level) {
                    GeohashChannelLevel.NEIGHBORHOOD -> ""
                    GeohashChannelLevel.CITY -> "CityName"
                    else -> ""
                }
            }

            override suspend fun reverseGeocodeAll(lat: Double, lon: Double): Map<GeohashChannelLevel, String> {
                return emptyMap()
            }
        }

        val result = resolveBookmarkName("u4pruy", geocoder)

        assertEquals("CityName", result)
    }
}
