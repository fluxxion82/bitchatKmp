package com.bitchat.local.prefs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The first-run-versus-load-failure decision. Getting this wrong in the FIRST_RUN direction
 * mints a new identity on top of a live one.
 */
class PreferenceStoreStateTest {

    @Test
    fun `an absent store is a first run`() {
        // The file was never written: nothing was read, so nothing can be damaged.
        assertEquals(
            PreferenceStoreState.FIRST_RUN,
            PreferenceStoreState.of(damage = emptyList(), isEmpty = true),
        )
    }

    @Test
    fun `an intact but empty store is a first run`() {
        // A file that exists and holds nothing is what clear() leaves behind, and minting is
        // exactly what the caller wants next.
        val decoded = FlatFileFormat.decode("")
        assertEquals(
            PreferenceStoreState.FIRST_RUN,
            PreferenceStoreState.of(decoded.damage, decoded.entries.isEmpty()),
        )
    }

    @Test
    fun `a corrupt store is unreadable, even when nothing at all could be parsed`() {
        val decoded = FlatFileFormat.decode("\u0000\u0001 not a preferences file")
        assertEquals(
            PreferenceStoreState.UNREADABLE,
            PreferenceStoreState.of(decoded.damage, decoded.entries.isEmpty()),
        )
    }

    @Test
    fun `a store that parsed but lost a record is unreadable, not populated`() {
        // Half-read is the dangerous case: real keys come back, so the store looks healthy,
        // but the key the caller wanted may be one of the ones that did not survive.
        val decoded = FlatFileFormat.decode("static_private_key=AAEC\nnostr_private_key=012345")
        assertEquals(
            PreferenceStoreState.UNREADABLE,
            PreferenceStoreState.of(decoded.damage, decoded.entries.isEmpty()),
        )
    }

    @Test
    fun `a partial store - intact, holding other keys but not this one - is populated`() {
        val decoded = FlatFileFormat.decode("signing_private_key=AAEC\nnickname=pi\n")
        assertEquals(
            PreferenceStoreState.POPULATED,
            PreferenceStoreState.of(decoded.damage, decoded.entries.isEmpty()),
        )
    }
}
