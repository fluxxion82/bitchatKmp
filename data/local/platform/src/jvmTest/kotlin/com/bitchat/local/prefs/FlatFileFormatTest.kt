package com.bitchat.local.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The embedded preference files are read and written by Kotlin/Native `linuxArm64` code that
 * cannot run on a developer machine, so the whole format lives in [FlatFileFormat] and is tested
 * here; the platform file is only POSIX plumbing around these two functions.
 */
class FlatFileFormatTest {

    // ---- round-tripping -----------------------------------------------------------------

    @Test
    fun `a value longer than the old 4096-byte read buffer survives a round trip`() {
        // The block list JSON already exceeds this. The old reader pulled the file through
        // fgets in 4096-byte chunks and treated each chunk as a line, so this value came back
        // cut at 4095 bytes and its tail was parsed as a separate record.
        val long = "x".repeat(70_000)
        val entries = mapOf("block_list" to long, "after" to "still here")

        val decoded = FlatFileFormat.decode(FlatFileFormat.encode(entries))

        assertEquals(entries, decoded.entries)
        assertEquals(70_000, decoded.entries.getValue("block_list").length)
        assertFalse(decoded.isDamaged, "a well-formed file is not damaged: ${decoded.damage}")
    }

    @Test
    fun `a value containing equals signs keeps every one of them`() {
        // Base64 pads with '='; the block list and peer-id index are JSON that can hold them.
        val entries = mapOf(
            "signing_private_key" to "c2VjcmV0LWtleS1tYXRlcmlhbA==",
            "equation" to "a=b=c",
            "empty" to "",
        )

        val decoded = FlatFileFormat.decode(FlatFileFormat.encode(entries))

        assertEquals(entries, decoded.entries)
        assertFalse(decoded.isDamaged, "a well-formed file is not damaged: ${decoded.damage}")
    }

    @Test
    fun `only the record's own newline is stripped, so values come back exactly as written`() {
        val entries = mapOf("nickname" to "  spaced  ", "tabbed" to "\tvalue\t")

        val decoded = FlatFileFormat.decode(FlatFileFormat.encode(entries))

        assertEquals(entries, decoded.entries)
    }

    @Test
    fun `an empty store encodes to an empty file and back`() {
        assertEquals("", FlatFileFormat.encode(emptyMap()))
        assertEquals(emptyMap(), FlatFileFormat.decode("").entries)
        assertFalse(FlatFileFormat.decode("").isDamaged)
    }

    // ---- fidelity with the reader this replaced ------------------------------------------

    @Test
    fun `data a previous build wrote decodes exactly as the old fgets reader decoded it`() {
        // Everything that survived a load-store cycle on the old code is already trimmed, so
        // for real on-disk data the two readers must agree exactly.
        val onDisk = FlatFileFormat.encode(
            mapOf(
                "static_private_key" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "static_public_key" to "IB8eHRwbGhkYFxYVFBMSERAPDg0MCwoJCAcGBQQDAgEA",
                "signing_private_key" to "ZmVmZTczNWZjMDYzZmYwOA==",
                "nostr_private_key" to "0123456789abcdef".repeat(4),
                "nickname" to "orangepi",
                "channels" to """meshDM:fefe735fc063ff08|pi""",
                "empty_value" to "",
            )
        )

        assertEquals(legacyDecode(onDisk), FlatFileFormat.decode(onDisk).entries)
    }

    @Test
    fun `the old reader really did mangle a long record, which is what this replaces`() {
        // Guards the claim above: the two readers agree on real data and disagree exactly where
        // the old one was broken. If this ever stops failing for the legacy oracle, the
        // 4096-byte bug was never there and the fidelity test proves nothing.
        val onDisk = FlatFileFormat.encode(mapOf("block_list" to "y".repeat(9_000)))

        val legacy = legacyDecode(onDisk)
        assertEquals(9_000, FlatFileFormat.decode(onDisk).entries.getValue("block_list").length)
        assertTrue(
            legacy.getValue("block_list").length < 9_000,
            "the legacy oracle should truncate at the buffer size, got ${legacy.getValue("block_list").length}"
        )
    }

    // ---- damage is reported, never swallowed ---------------------------------------------

    @Test
    fun `a file cut short mid-write is reported, and what survived is still returned`() {
        // The writer terminates every record, so a missing final newline means the write did
        // not finish: a power cut, a full disk, or the old truncate-then-write save.
        val truncated = "static_private_key=AAEC\nnostr_private_key=0123456789ab"

        val decoded = FlatFileFormat.decode(truncated)

        assertTrue(decoded.isDamaged, "a file with no trailing newline must be reported")
        assertTrue(
            decoded.damage.any { it.contains("truncated") },
            "expected the truncation named, got ${decoded.damage}"
        )
        // Reporting is not the same as discarding: the good record is still handed back.
        assertEquals("AAEC", decoded.entries["static_private_key"])
    }

    @Test
    fun `a line with no separator is reported rather than silently dropped`() {
        val corrupt = "static_private_key=AAEC\nWKgAAAAAAAAAgarbage\nnickname=pi\n"

        val decoded = FlatFileFormat.decode(corrupt)

        assertTrue(decoded.isDamaged)
        assertTrue(
            decoded.damage.any { it.contains("line 2") && it.contains("no '=' separator") },
            "expected line 2 named, got ${decoded.damage}"
        )
        assertEquals(mapOf("static_private_key" to "AAEC", "nickname" to "pi"), decoded.entries)
    }

    @Test
    fun `an empty key and a duplicate key are both reported`() {
        val corrupt = "=orphan\nnickname=pi\nnickname=pi2\n"

        val decoded = FlatFileFormat.decode(corrupt)

        assertTrue(decoded.damage.any { it.contains("line 1") && it.contains("empty key") }, "${decoded.damage}")
        assertTrue(decoded.damage.any { it.contains("line 3") && it.contains("duplicate") }, "${decoded.damage}")
        assertEquals("pi2", decoded.entries["nickname"])
    }

    @Test
    fun `a wholly unparseable file yields no entries but does not pass for empty`() {
        // The failure this exists to prevent: garbage in, empty map out, caller concludes
        // "first run" and mints a new identity over the top.
        val decoded = FlatFileFormat.decode("\u0000\u0001binary rubbish")

        assertEquals(emptyMap(), decoded.entries)
        assertTrue(decoded.isDamaged, "an unparseable file must not look like an empty store")
        assertEquals(
            PreferenceStoreState.UNREADABLE,
            PreferenceStoreState.of(decoded.damage, decoded.entries.isEmpty()),
        )
    }

    /**
     * What the removed reader did, kept as the fidelity oracle: `fgets` into a 4096-byte buffer
     * (so at most 4095 bytes per call, stopping after a newline), `String.trim()` on the chunk,
     * split on the first `=`, chunks without one dropped.
     */
    private fun legacyDecode(text: String, bufferSize: Int = 4096): Map<String, String> {
        val bytes = text.encodeToByteArray()
        val out = LinkedHashMap<String, String>()
        var index = 0
        while (index < bytes.size) {
            var end = index
            var taken = 0
            while (end < bytes.size && taken < bufferSize - 1) {
                val byte = bytes[end]
                end++
                taken++
                if (byte == '\n'.code.toByte()) break
            }
            val line = bytes.decodeToString(index, end).trim()
            index = end
            if (line.isNotEmpty() && line.contains('=')) {
                val separator = line.indexOf('=')
                out[line.substring(0, separator)] = line.substring(separator + 1)
            }
        }
        return out
    }
}
