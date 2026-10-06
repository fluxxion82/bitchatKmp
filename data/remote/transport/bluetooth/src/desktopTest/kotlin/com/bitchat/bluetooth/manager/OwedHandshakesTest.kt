package com.bitchat.bluetooth.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwedHandshakesTest {

    @Test
    fun whatThisNodeStartedByItselfNeverPushesOutWhatTheUserAskedFor() {
        val owed = OwedHandshakes(capacity = 3)
        listOf("alice", "bob", "carol").forEach(owed::rememberForUser)

        repeat(1_000) { owed.rememberAutomatic("invented-$it") }

        assertTrue("alice" in owed && "bob" in owed && "carol" in owed)
        assertEquals(3, owed.userCount)
        assertEquals(3, owed.automaticCount)
    }

    @Test
    fun eachKindDropsItsOwnOldestWhenFull() {
        val owed = OwedHandshakes(capacity = 2)
        owed.rememberForUser("alice")
        owed.rememberForUser("bob")
        owed.rememberForUser("carol")
        owed.rememberAutomatic("x")
        owed.rememberAutomatic("y")
        owed.rememberAutomatic("z")

        assertFalse("alice" in owed)
        assertTrue("bob" in owed && "carol" in owed)
        assertFalse("x" in owed)
        assertTrue("y" in owed && "z" in owed)
    }

    @Test
    fun anEntryAlreadyPresentKeepsItsPlace() {
        val owed = OwedHandshakes(capacity = 2)
        owed.rememberForUser("alice")
        owed.rememberForUser("bob")
        owed.rememberForUser("alice")
        owed.rememberForUser("carol")

        // "alice" was still the oldest, so asking for her again did not save her place.
        assertFalse("alice" in owed)
        assertTrue("bob" in owed && "carol" in owed)
    }

    @Test
    fun theUsersRequestTakesOverAnAutomaticEntryAndIsNotTakenBack() {
        val owed = OwedHandshakes(capacity = 2)
        owed.rememberAutomatic("alice")
        assertFalse(owed.isForUser("alice"))

        owed.rememberForUser("alice")
        assertTrue(owed.isForUser("alice"))
        assertEquals(0, owed.automaticCount)

        owed.rememberAutomatic("alice")
        assertTrue(owed.isForUser("alice"))
        assertEquals(0, owed.automaticCount)
    }

    @Test
    fun removeForgetsBothKinds() {
        val owed = OwedHandshakes()
        owed.rememberForUser("alice")
        owed.rememberAutomatic("bob")

        owed.remove("alice")
        owed.remove("bob")

        assertFalse("alice" in owed)
        assertFalse("bob" in owed)
        assertEquals(0, owed.userCount + owed.automaticCount)
    }
}
