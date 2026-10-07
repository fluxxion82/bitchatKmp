package com.bitchat.nostr.util

import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NostrRelay passes every incoming event through [NostrEventDeduplicator.processEvent] before any
 * subscription handler sees it, so an event dropped here never reaches ChatRepo or LocationRepo at
 * all. These run wherever commonTest runs, so against each platform's own signature check.
 */
class NostrEventDeduplicatorTest {
    private val client = dmClient()
    private val sender = NostrIdentity.generate()
    private val recipient = NostrIdentity.generate()
    private val impostor = NostrIdentity.generate()

    @Test
    fun `a gift wrap relabelled with a real wrap's id does not stop the real one`() {
        val deduplicator = NostrEventDeduplicator()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)
        val relabelled = client.giftWrap("bitchat1:decoy", NostrIdentity.generate(), recipient).copy(id = genuine.id)

        assertEquals(listOf(genuine), deduplicator.processAll(relabelled, genuine))
    }

    @Test
    fun `a real gift wrap under a forged signature does not stop the real one`() {
        val deduplicator = NostrEventDeduplicator()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)
        val forged = genuine.copy(sig = client.giftWrap("bitchat1:other", sender, recipient).sig)

        assertEquals(listOf(genuine), deduplicator.processAll(forged, genuine))
    }

    @Test
    fun `a gift wrap arriving from several relays is processed once`() {
        val deduplicator = NostrEventDeduplicator()
        val genuine = client.giftWrap("bitchat1:real", sender, recipient)

        assertEquals(listOf(genuine), deduplicator.processAll(genuine, genuine.copy(), genuine.copy()))
    }

    @Test
    fun `an event of any kind is handed on once when it verifies`() {
        ALL_KINDS.forEach { kind ->
            val deduplicator = NostrEventDeduplicator()
            val genuine = sender.signed(kind, "hello")
            val handedOn = mutableListOf<NostrEvent>()

            val admissions = listOf(genuine, genuine.copy(), genuine.copy()).map { copy -> deduplicator.processEvent(copy) { handedOn += it } }

            assertEquals(listOf(EventAdmission.DELIVERED, EventAdmission.DUPLICATE, EventAdmission.DUPLICATE), admissions, "kind $kind")
            assertEquals(listOf(genuine), handedOn, "kind $kind")
        }
    }

    @Test
    fun `an event of any kind is refused when a field differs from what was signed`() {
        ALL_KINDS.forEach { kind ->
            val genuine = sender.signed(kind, "hello")
            val altered = mapOf(
                "content" to genuine.copy(content = "send me your keys"),
                "key" to genuine.copy(pubkey = impostor.publicKeyHex),
                "time" to genuine.copy(createdAt = genuine.createdAt + 1),
                "kind" to genuine.copy(kind = kind + 1),
                "tags" to genuine.copy(tags = genuine.tags + listOf(listOf("n", "someone else"))),
                "id" to genuine.copy(id = "0".repeat(64)),
            )

            altered.forEach { (field, event) ->
                assertEquals(emptyList(), NostrEventDeduplicator().processAll(event), "kind $kind, $field changed")
            }
        }
    }

    @Test
    fun `an event is refused when its signature is missing or malformed or someone else's`() {
        val genuine = sender.signed(NostrKind.EPHEMERAL_EVENT, "hello")
        // What a relay that wants to speak as the sender can produce: the sender's key, a correct id,
        // and a signature made with a key the relay does hold.
        val asSender = impostor.signed(NostrKind.EPHEMERAL_EVENT, "hello").copy(pubkey = sender.publicKeyHex)
            .let { it.copy(id = it.computeEventIdHex()) }
        val refused = mapOf(
            "no signature" to genuine.copy(sig = null),
            "empty signature" to genuine.copy(sig = ""),
            "not hex" to genuine.copy(sig = "z".repeat(128)),
            "too short" to genuine.copy(sig = genuine.sig!!.dropLast(2)),
            "signature of another event" to genuine.copy(sig = sender.signed(NostrKind.EPHEMERAL_EVENT, "other").sig),
            "signed by another key" to asSender,
            "no id" to genuine.copy(id = ""),
            "id in upper case" to genuine.copy(id = genuine.id.uppercase()),
        )

        refused.forEach { (what, event) ->
            assertEquals(EventAdmission.INVALID to emptyList(), NostrEventDeduplicator().admit(event), what)
        }
    }

    @Test
    fun `text that has no UTF-8 form is refused rather than hashed as something else`() {
        // Half a surrogate pair cannot be written as UTF-8. Encoders put something in its place - a
        // question mark on the JVM, U+FFFD elsewhere - so an event carrying the stray half hashes
        // like the event that really has that character there, and the signature covers both.
        val strayHalves = listOf(Char(0xD800), Char(0xDBFF), Char(0xDC00), Char(0xDFFF))
        val stoodInFor = listOf("?", Char(0xFFFD).toString())

        for (stray in strayHalves) for (stand in stoodInFor) {
            val genuine = sender.signed(NostrKind.EPHEMERAL_EVENT, "who${stand}")
            val inContent = genuine.copy(content = "who$stray")
            val inTag = sender.signed(NostrKind.EPHEMERAL_EVENT, "hi", name = "al${stand}ce").let { signedName ->
                signedName.copy(tags = listOf(listOf("g", "u4pruy"), listOf("n", "al${stray}ce")))
            }
            val deduplicator = NostrEventDeduplicator()

            assertEquals(EventAdmission.INVALID to emptyList(), deduplicator.admit(inContent), "content, U+${stray.code.toString(16)} for '$stand'")
            assertEquals(EventAdmission.INVALID to emptyList(), deduplicator.admit(inTag), "tag, U+${stray.code.toString(16)} for '$stand'")
            assertEquals(listOf(genuine), deduplicator.processAll(genuine), "the real event is not stopped by the altered copy")
        }
    }

    @Test
    fun `whole surrogate pairs are ordinary text`() {
        val pair = "${Char(0xD83D)}${Char(0xDE00)}"
        val genuine = sender.signed(NostrKind.EPHEMERAL_EVENT, "hello $pair", name = "al${pair}ce")

        assertEquals(listOf(genuine), NostrEventDeduplicator().processAll(genuine))
    }

    @Test
    fun `an event that is refused leaves nothing recorded`() {
        val deduplicator = NostrEventDeduplicator()
        val genuine = sender.signed(NostrKind.EPHEMERAL_EVENT, "hello")
        val decoy = impostor.signed(NostrKind.EPHEMERAL_EVENT, "something else").copy(id = genuine.id)

        repeat(3) { assertEquals(EventAdmission.INVALID to emptyList(), deduplicator.admit(decoy)) }

        assertEquals(0, deduplicator.size())
        assertEquals(EventAdmission.DELIVERED to listOf(genuine), deduplicator.admit(genuine))
    }

    @Test
    fun `a copy of an event already handed on is dropped without being verified`() {
        var checks = 0
        val deduplicator = NostrEventDeduplicator(maxCapacity = 10) { event ->
            checks++
            event.isValidSignature()
        }
        val genuine = sender.signed(NostrKind.EPHEMERAL_EVENT, "hello")
        val unverifiable = genuine.copy(sig = null)

        // Before the real event, a copy that does not verify is looked at and refused.
        assertEquals(EventAdmission.INVALID to emptyList(), deduplicator.admit(unverifiable))
        assertEquals(EventAdmission.DELIVERED to listOf(genuine), deduplicator.admit(genuine))
        assertEquals(2, checks)

        // After it the id alone settles it, for a true copy and for one that would not verify:
        // this is what makes the copies the other relays send cost no signature check.
        assertEquals(EventAdmission.DUPLICATE to emptyList(), deduplicator.admit(genuine.copy()))
        assertEquals(EventAdmission.DUPLICATE to emptyList(), deduplicator.admit(unverifiable))
        assertEquals(2, checks, "a copy of a recorded event was verified")
    }

    @Test
    fun `an event with nothing in it verifies like any other`() {
        // A presence event has no content; verifying must not ask for more than the signature does.
        val presence = sender.signEvent(
            NostrEvent(pubkey = sender.publicKeyHex, createdAt = 1_780_000_000, kind = 20001, tags = emptyList(), content = "")
        )

        assertEquals(listOf(presence), NostrEventDeduplicator().processAll(presence))
    }

    @Test
    fun `an id pushed out of the record is verified again and not trusted`() {
        val deduplicator = NostrEventDeduplicator(maxCapacity = 2)
        val first = sender.signed(NostrKind.EPHEMERAL_EVENT, "first")
        deduplicator.processAll(first, sender.signed(NostrKind.EPHEMERAL_EVENT, "second"), sender.signed(NostrKind.EPHEMERAL_EVENT, "third"))

        assertEquals(EventAdmission.INVALID to emptyList(), deduplicator.admit(first.copy(content = "altered")))
        assertEquals(EventAdmission.DELIVERED to listOf(first), deduplicator.admit(first))
    }

    /** What the gate said about [event], and what it handed on. */
    private fun NostrEventDeduplicator.admit(event: NostrEvent): Pair<EventAdmission, List<NostrEvent>> {
        val handedOn = mutableListOf<NostrEvent>()
        return processEvent(event) { handedOn += it } to handedOn
    }

    private fun NostrEventDeduplicator.processAll(vararg events: NostrEvent): List<NostrEvent> {
        val processed = mutableListOf<NostrEvent>()
        events.forEach { event -> processEvent(event) { processed += it } }
        return processed
    }

    private fun NostrIdentity.signed(kind: Int, content: String, name: String = "alice"): NostrEvent = signEvent(
        NostrEvent(
            pubkey = publicKeyHex,
            createdAt = 1_780_000_000,
            kind = kind,
            tags = listOf(listOf("g", "u4pruy"), listOf("n", name)),
            content = content,
        )
    )

    private companion object {
        /** Every kind this app subscribes to, the presence kind the other clients send, and one it has never heard of. */
        val ALL_KINDS = listOf(
            NostrKind.METADATA,
            NostrKind.TEXT_NOTE,
            NostrKind.CHANNEL_CREATE,
            NostrKind.CHANNEL_MESSAGE,
            NostrKind.GIFT_WRAP,
            NostrKind.EPHEMERAL_EVENT,
            20001,
            31_337,
        )
    }
}
