package com.bitchat.nostr.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An event is accepted only if its id is the SHA-256 of its NIP-01 serialization, so this client has
 * to serialize an event exactly as the client that signed it and the relay that carried it did:
 * one byte of difference in how a character is written and a genuine message is thrown away.
 *
 * The ids below were not produced by this code. Each is the SHA-256 of the UTF-8 bytes of
 * `[0,pubkey,created_at,kind,tags,content]` written by Python's `json.dumps` with separators
 * `(",", ":")` and `ensure_ascii=False`: no whitespace, the short escapes NIP-01 names, the other
 * control characters as lower-case `\u00XX`, everything else as it is. The text is used both as
 * the content and as a tag value.
 */
class NostrEventIdTest {
    private class Vector(val what: String, val text: String, val id: String)

    private val vectors = listOf(
        Vector(
            what = "plain ASCII",
            text = "hello from u4pruy",
            id = "84a47ef5a5eabb3c5be76f87e4a2dbfec3605cb6111b98bfa48197e5f888d39e",
        ),
        Vector(
            what = "quote, backslash and slash",
            text = "quote \" backslash \\ slash / end",
            id = "6765d51b23ede48218a4752ff413e66630f13d803047a2024fa16b1963f5627a",
        ),
        Vector(
            what = "the escapes NIP-01 names",
            text = "line\u000Abreak\u000D\u0009tab\u0008\u000C",
            id = "4dfae012165dfc1b7717710590c9dc0ea1bd90941bb090f4cb9d00589c653bfb",
        ),
        Vector(
            what = "other control characters and DEL",
            text = "ctrl \u0001 \u001F del \u007F",
            id = "d99f2d12563dd7ff48b9e63b6cc9e871950f0ae9177c19ea5cf24a293aa72587",
        ),
        Vector(
            what = "line and paragraph separators",
            text = "ls \u2028 ps \u2029",
            id = "c03b24d18f250259dbfaff444011cb4c849d6d9914ebb1bab81e368756d22ad2",
        ),
        Vector(
            what = "emoji, a flag and CJK",
            text = "\uD83D\uDE00 \uD83C\uDDFA\uD83C\uDDF8 \u65E5\u672C\u8A9E",
            id = "f9916be3a4a1d7b203637552472df6696622e0767859137da45fd890525db639",
        ),
        Vector(
            what = "characters HTML escapers touch",
            text = "<html>&amp;'=",
            id = "aca8b6de9b800fac741ae792752a2008687a7796feb4649e2f9c190840d2403a",
        ),
        Vector(
            what = "composed, combining, BOM and no-break space",
            text = "\u00E9 e\u0301 \uFEFF \u00A0",
            id = "6f8ec23dc745d934166ad8fd7a8dbbd902535093554c6f1f6d3a6271d950e4d2",
        ),
        Vector(
            what = "bidi controls",
            text = "\u202Eevil\u2066\u2069",
            id = "d3641772d6b6e78547c33baa753d7319eb0f762cc46a4a6354993aac41b41154",
        ),
        Vector(
            what = "a dollar sign and braces",
            text = "\${not} \$a template",
            id = "d3b322e9b1ad5029317a4ba927c8d07ed334710fdce8aeb1b9799ffa96fc1d0f",
        ),
        Vector(
            what = "nothing",
            text = "",
            id = "ab1523f693e44a83488450220e1c6be4c10970247f53f4354a003855f908d383",
        ),
    )

    @Test
    fun `an event's id is the hash of its NIP-01 serialization whatever characters it holds`() {
        vectors.forEach { vector ->
            val event = NostrEvent(
                pubkey = "a".repeat(64),
                createdAt = 1_780_000_000,
                kind = NostrKind.EPHEMERAL_EVENT,
                tags = listOf(listOf("g", "u4pruy"), listOf("n", vector.text)),
                content = vector.text,
            )

            assertEquals(vector.id, event.computeEventIdHex(), vector.what)
        }
    }
}
