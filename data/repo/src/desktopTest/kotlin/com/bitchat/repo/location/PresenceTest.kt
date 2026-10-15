package com.bitchat.repo.location

import com.bitchat.domain.location.model.GeoPoint
import com.bitchat.domain.location.model.LocationFixInfo.Source
import com.bitchat.domain.location.model.ObservedFix
import com.bitchat.domain.location.model.SavedPlace
import com.bitchat.nostr.util.GeohashUtils
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresenceTest {
    private val here = GeoPoint(37.7749, -122.4194)
    private fun cell(precision: Int) = GeohashUtils.encode(here.lat, here.lon, precision)
    private fun device(ageMillis: Long = 0) = ObservedFix(here, Source.DEVICE, ageMillis)
    private fun ip(ageMillis: Long = 0) = ObservedFix(here, Source.IP_ADDRESS, ageMillis)
    private fun saved(geohash: String) = requireNotNull(SavedPlace.of(geohash))

    @Test
    fun `a saved place contains each prefix of itself and nothing longer or beside it`() {
        val place = saved("9q8yy")
        assertTrue(isPresent("9q8yy", place, null))
        assertTrue(isPresent("9q8y", place, null))
        assertTrue(isPresent("9q", place, null))
        assertFalse(isPresent("9q8yz", place, null))
        assertFalse(isPresent("9q8yyb", place, null))
        assertFalse(isPresent("u3", place, null))
    }

    @Test
    fun `a saved place decides alone whatever the fix says`() {
        val elsewhere = saved("u33db")
        assertFalse(isPresent(cell(5), elsewhere, device()))
        assertTrue(isPresent("u33d", elsewhere, ObservedFix(GeoPoint(0.0, 0.0), Source.DEVICE, 0)))
    }

    @Test
    fun `a channel is compared as a saved place is written`() {
        assertTrue(isPresent(" #9Q8YY ", saved("9q8yy"), null))
        assertTrue(isPresent("#" + cell(5).uppercase(), null, device()))
    }

    @Test
    fun `a device fix is here at every precision of its own cell`() {
        for (precision in listOf(2, 4, 5, 6, 7, 8, 12)) {
            assertTrue(isPresent(cell(precision), null, device()), "precision $precision")
        }
    }

    @Test
    fun `a device fix is not here in a neighbouring cell`() {
        val neighbour = GeohashUtils.neighbors(cell(7)).first()
        assertFalse(isPresent(neighbour, null, device()))
    }

    @Test
    fun `a device fix counts up to an hour old and not after`() {
        assertTrue(isPresent(cell(7), null, device(3_600_000L)))
        assertFalse(isPresent(cell(7), null, device(3_600_001L)))
    }

    @Test
    fun `a fix with a negative age is not here`() {
        assertFalse(isPresent(cell(7), null, device(-1)))
        assertFalse(isPresent(cell(5), null, ip(-1)))
    }

    @Test
    fun `an address fix is here for city province and region only`() {
        assertTrue(isPresent(cell(5), null, ip()))
        assertTrue(isPresent(cell(4), null, ip()))
        assertTrue(isPresent(cell(2), null, ip()))
        assertFalse(isPresent(cell(6), null, ip()))
        assertFalse(isPresent(cell(7), null, ip()))
    }

    @Test
    fun `an address fix counts up to an hour old and not after`() {
        assertTrue(isPresent(cell(5), null, ip(3_600_000L)))
        assertFalse(isPresent(cell(5), null, ip(3_600_001L)))
    }

    @Test
    fun `an address fix is not here in another city`() {
        val neighbour = GeohashUtils.neighbors(cell(5)).first()
        assertFalse(isPresent(neighbour, null, ip()))
    }

    @Test
    fun `a fix from another source is never here`() {
        assertFalse(isPresent(cell(5), null, ObservedFix(here, Source.MANUAL, 0)))
        assertFalse(isPresent(cell(5), null, ObservedFix(here, Source.DEFAULT, 0)))
    }

    @Test
    fun `nothing known is not here`() {
        assertFalse(isPresent(cell(5), null, null))
    }

    @Test
    fun `a channel that is no geohash is not here`() {
        for (channel in listOf("", "9", "9q8yy!", "9q8ya", "9q8yyk8yyk8yy")) {
            assertFalse(isPresent(channel, null, device()), "channel '$channel'")
            assertFalse(isPresent(channel, saved("9q8yyk8yyk8y"), null), "channel '$channel' with a place")
        }
    }

    @Test
    fun `a fix that is no point on Earth is not here and does not throw`() {
        assertFalse(isPresent(cell(5), null, ObservedFix(GeoPoint(Double.NaN, 0.0), Source.DEVICE, 0)))
    }
}
