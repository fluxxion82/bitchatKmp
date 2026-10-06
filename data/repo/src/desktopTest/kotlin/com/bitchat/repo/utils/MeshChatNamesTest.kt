package com.bitchat.repo.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeshChatNamesTest {
    @Test fun anAnnouncedNameIsSanitisedAndKeepsNoNumberSign() {
        assertEquals("alice", announcedMeshName(" ali\nce "))
        assertEquals("aliceffff", announcedMeshName("alice#ffff"))
    }

    @Test fun nothingAndThePlaceholderAnnounceNoName() {
        assertNull(announcedMeshName(null))
        assertNull(announcedMeshName(" \n "))
        assertNull(announcedMeshName("Unknown"))
    }

    @Test fun anAnnouncedNameMayBeTheStartOfThePeersOwnId() {
        // Only a name the app handed back is suspected of being the id it showed in place of a name.
        assertEquals(ID.take(12), announcedMeshName(ID.take(12)))
    }

    @Test fun aNameHandedBackIsSanitised() {
        assertEquals("alice", meshNameHandedBack(" ali\nce ", ID))
    }

    @Test fun aChatsOwnNameHandedBackLosesTheSuffixTheAppGaveIt() {
        assertEquals("alice", meshNameHandedBack("alice#1a2b", ID))
        assertEquals("alice", meshNameHandedBack(" alice#1a2b ", ID))
    }

    @Test fun aNameOfFullLengthHandedBackWithItsSuffixLosesOnlyTheSuffix() {
        for (length in 44..50) {
            val name = "a".repeat(length)
            assertEquals(name, meshNameHandedBack("$name#1a2b", ID), "a name of $length characters")
        }
    }

    @Test fun anotherPeersSuffixHandedBackIsNotTakenForThisPeers() {
        assertEquals("aliceffff", meshNameHandedBack("alice#ffff", ID))
    }

    @Test fun nothingAndThePlaceholderHandedBackAreNoName() {
        assertNull(meshNameHandedBack(null, ID))
        assertNull(meshNameHandedBack("", ID))
        assertNull(meshNameHandedBack("Unknown", ID))
        assertNull(meshNameHandedBack("#1a2b", ID))
    }

    @Test fun theStartOfThePeersOwnIdHandedBackIsNoName() {
        assertNull(meshNameHandedBack(ID.take(12), ID))
        assertNull(meshNameHandedBack(ID.take(12).uppercase(), ID))
        assertNull(meshNameHandedBack(ID, ID))
    }

    @Test fun aShortNameThatHappensToStartTheIdIsAName() {
        assertEquals("1a2b", meshNameHandedBack("1a2b", ID))
        assertEquals("1", meshNameHandedBack("1", ID))
    }

    @Test fun theStartOfAnotherPeersIdIsAName() {
        assertEquals("2b3c4d5e6f78", meshNameHandedBack("2b3c4d5e6f78", ID))
    }

    private companion object {
        const val ID = "1a2b3c4d5e6f7890"
    }
}
