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
    fun anOpeningIsToComeOnlyForAUserWhoseOwnOpeningDidNotLeave() {
        val owed = OwedHandshakes()
        owed.rememberForUser("alice", openingToCome = true)
        owed.rememberForUser("bob")
        owed.rememberAutomatic("carol")

        assertEquals(listOf("alice"), owed.takeOpeningsToCome { true })
    }

    @Test
    fun anOpeningThatIsToComeStaysSoWhateverIsRememberedForItsPeerAfterwards() {
        val owed = OwedHandshakes()
        owed.rememberForUser("alice", openingToCome = true)
        // A restart this node makes by itself for a handshake that is the user's.
        owed.rememberForUser("alice")
        owed.rememberAutomatic("alice")

        assertEquals(listOf("alice"), owed.takeOpeningsToCome { true })
    }

    @Test
    fun anOpeningIsTakenOnceAndOnlyForAPeerThatIsReadyForIt() {
        val owed = OwedHandshakes()
        listOf("alice", "bob", "carol").forEach { owed.rememberForUser(it, openingToCome = true) }

        assertEquals(listOf("alice", "carol"), owed.takeOpeningsToCome { it != "bob" })
        // Taken: not to come again. Bob's was left alone, and the entries are all still there.
        assertEquals(listOf("bob"), owed.takeOpeningsToCome { true })
        assertEquals(emptyList(), owed.takeOpeningsToCome { true })
        assertEquals(3, owed.userCount)

        // Whoever took one and could not send it says so.
        owed.rememberForUser("carol", openingToCome = true)
        assertEquals(listOf("carol"), owed.takeOpeningsToCome { true })
    }

    @Test
    fun anOpeningThatIsToComeGoesWithItsEntry() {
        val owed = OwedHandshakes(capacity = 2)
        owed.rememberForUser("alice", openingToCome = true)
        owed.rememberForUser("bob", openingToCome = true)
        owed.remove("bob")
        // Pushes alice out; bob comes back without anything said of an opening.
        owed.rememberForUser("carol")
        owed.rememberForUser("bob")
        owed.rememberForUser("alice")

        assertEquals(emptyList(), owed.takeOpeningsToCome { true })
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
