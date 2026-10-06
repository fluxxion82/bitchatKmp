package com.bitchat.mediautils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceivedFileNameTest {

    private val names = listOf(
        "photo.jpg" to "photo.jpg",
        "my photo (1).jpg" to "my_photo__1_.jpg",
        "../../etc/passwd" to "_.._etc_passwd",
        "/etc/passwd" to "_etc_passwd",
        "..\\..\\x.txt" to "_.._x.txt",
        ".." to "file",
        "." to "file",
        "" to "file",
        "..." to "file",
        ".bashrc" to "bashrc",
        "a${0.toChar()}b\n${27.toChar()}c" to "a_b__c",
        "café.txt" to "caf_.txt",
        "x".repeat(300) + ".jpeg" to "x".repeat(95) + ".jpeg",
        "x".repeat(300) to "x".repeat(100),
        // Names Windows gives a meaning of its own in every directory: device names, whatever their
        // extension or case, and trailing dots (which it drops, so "x." and "x" are one file there).
        "CON" to "_CON",
        "nul.txt" to "_nul.txt",
        "COM1" to "_COM1",
        "LPT9.tar.gz" to "_LPT9.tar.gz",
        "aux." to "_aux",
        "CONSOLE.txt" to "CONSOLE.txt",
        "com10" to "com10",
        "report..." to "report",
        // Cut at 100 right after a dot (the "extension" is too long to be one): no trailing dot is left.
        "x".repeat(99) + "." + "y".repeat(30) to "x".repeat(99),
    )

    @Test
    fun makesSenderNamesSafeForAFilePathAndUiText() {
        names.forEach { (input, expected) ->
            assertEquals(expected, safeReceivedFileName(input), input)
        }
    }

    @Test
    fun safeNamesAlwaysArePlainAndVisibleAsciiNames() {
        val allowed = Regex("^[A-Za-z0-9._-]{1,100}$")

        names.forEach { (input, _) ->
            val safeName = safeReceivedFileName(input)
            assertTrue(isPlainFileName(safeName), input)
            assertTrue(allowed.matches(safeName), input)
            assertFalse(safeName.startsWith('.'), input)
            assertFalse(safeName.endsWith('.'), input)
        }
    }

    @Test
    fun recognizesOnlyPlainPathComponents() {
        listOf("photo.jpg", "file", "a_b-c.1").forEach { name ->
            assertTrue(isPlainFileName(name), name)
        }
        listOf("", ".", "..", "a/b", "a\\b", "a${0.toChar()}b").forEach { name ->
            assertFalse(isPlainFileName(name), name)
        }
    }
}
