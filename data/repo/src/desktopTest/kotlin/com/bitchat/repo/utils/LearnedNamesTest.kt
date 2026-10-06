package com.bitchat.repo.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearnedNamesTest {
    @Test
    fun dropsTheOldestLearnedNameAtCapacity() {
        val names = LearnedNames(capacity = 2)
        names.learn("one", "one")
        names.learn("two", "two")
        names.learn("three", "three")

        assertNull(names["one"])
        assertEquals("two", names["two"])
        assertEquals("three", names["three"])
    }

    @Test
    fun renamingKnownKeyDoesNotMoveIt() {
        val names = LearnedNames(capacity = 2)
        names.learn("one", "old")
        names.learn("two", "two")
        names.learn("one", "new")
        names.learn("three", "three")

        assertNull(names["one"])
        assertEquals("two", names["two"])
    }

    @Test
    fun rememberedNameWinsAndSurvivesTraffic() {
        val names = LearnedNames(capacity = 2)
        names.learn("person", "learned")
        names.remember("person", "chosen")
        assertEquals("chosen", names["person"], "with both known, the user's own")

        repeat(10) { names.learn("peer-$it", "name-$it") }
        names.learn("person", "learned again")
        assertEquals("chosen", names["person"])
    }

    @Test
    fun clearForgetsLearnedAndRememberedNames() {
        val names = LearnedNames()
        names.learn("learned", "name")
        names.remember("remembered", "name")
        names.clear()

        assertNull(names["learned"])
        assertNull(names["remembered"])
    }
}
