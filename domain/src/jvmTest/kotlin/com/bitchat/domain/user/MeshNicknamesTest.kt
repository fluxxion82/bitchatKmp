package com.bitchat.domain.user

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeshNicknamesTest {
    @Test fun aMeshNicknameKeepsNoNumberSign() {
        assertEquals("alice1a2b", sanitizedMeshNickname("alice#1a2b"))
        assertEquals("cdev", sanitizedMeshNickname("c#dev#"))
    }

    @Test fun theSameSignInAnotherWidthIsNotKeptEither() {
        assertEquals("alice1a2b", sanitizedMeshNickname("alice\uFF031a2b"))
        assertEquals("alice1a2b", sanitizedMeshNickname("alice\uFE5F1a2b"))
    }

    @Test fun aMeshNicknameIsSanitisedLikeAnyOther() {
        assertEquals("alice", sanitizedMeshNickname(" ali\nce\u202E "))
        assertEquals(MAX_NICKNAME_CHARS, sanitizedMeshNickname("x".repeat(300))?.length)
    }

    @Test fun nothingButNumberSignsIsNoNickname() {
        assertNull(sanitizedMeshNickname("##"))
        assertNull(sanitizedMeshNickname(" # "))
        assertNull(sanitizedMeshNickname(null))
    }

    @Test fun aNicknameOnOtherTransportsKeepsItsNumberSign() {
        assertEquals("alice#1a2b", sanitizedNickname("alice#1a2b"))
    }

    @Test fun aChatsNameIsTheClaimAndTheStartOfTheId() {
        assertEquals("alice#1a2b", meshChatName("alice", "1a2b3c4d5e6f7890"))
    }

    @Test fun theSuffixIsLowerCaseWhateverTheIdsCase() {
        assertEquals("alice#1a2b", meshChatName("alice", "1A2B3C4D5E6F7890"))
        assertEquals("#1a2b", meshChatNameSuffix("1A2B3C4D5E6F7890"))
    }
}
