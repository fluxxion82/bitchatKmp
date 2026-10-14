package com.bitchat.domain.location.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedPlaceTest {
    @Test
    fun `accepts bounds prefix and upper case`() {
        assertEquals("9q", SavedPlace.of("9q")?.geohash)
        assertEquals("9q8yyk8ytpxr", SavedPlace.of("9Q8YYK8YTPXR")?.geohash)
        assertEquals("9q8yy", SavedPlace.of(" #9Q8YY ")?.geohash)
    }

    @Test
    fun `refuses invalid geohashes`() {
        listOf("9", "9q8yyk8ytpxr0", "a2", "i2", "l2", "o2", "9 q", "").forEach {
            assertNull(SavedPlace.of(it), it)
        }
    }
}
