package com.bitchat.tui

import com.bitchat.domain.chat.model.failure.CommandFailure
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.location.model.Note
import com.bitchat.viewvo.chat.HeaderState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class BindingTest {
    @Test fun commandFailuresReadLikeTheComposeApp() {
        assertEquals("command requires a target", commandFailureMessage(CommandFailure.MissingTarget))
        assertEquals("command requires a channel", commandFailureMessage(CommandFailure.RequiresChannel))
        assertEquals("command only available in location channels", commandFailureMessage(CommandFailure.RequiresLocation))
        assertEquals("command only available from mesh channel", commandFailureMessage(CommandFailure.RequiresMesh))
        assertEquals("command only available in named channels", commandFailureMessage(CommandFailure.RequiresNamedChannel))
        assertEquals("no such user", commandFailureMessage(CommandFailure.Unknown("no such user")))
        assertEquals("invalid command", commandFailureMessage(CommandFailure.Unknown("  ")))
    }

    @Test fun notesReadAsAConversation() {
        // Newest first, which is how the relay hands them over.
        val notes = listOf(
            Note("n2", "ef567890", "no nickname", 43_260, null),
            Note("n1", "abcd1234", "hello here", 43_200, "alice"),
        )
        val messages = noteMessages(notes)
        assertEquals(listOf("alice#1234", "anon#7890"), messages.map { it.sender }, "as the Compose app names them")
        assertEquals(listOf("hello here", "no nickname"), messages.map { it.content }, "oldest first, as a chat reads")
        assertEquals(listOf("abcd1234", "ef567890"), messages.map { it.senderPeerID }, "the key is what colours them")
        assertEquals(Instant.fromEpochSeconds(43_200), messages.first().timestamp)
    }

    @Test fun erasingTheDraftsLeavesNothingToRecall() {
        val editor = LineEditor()
        editor.insert("a secret")
        editor.submit()
        editor.insert("half typed")
        eraseDrafts(editor)
        assertEquals("", editor.text)
        editor.historyUp()
        assertEquals("", editor.text, "the wipe takes what was sent as well as what was typed")
        editor.historyDown()
        assertEquals("", editor.text)
    }

    @Test fun torBlocksNostrOnlyWhileItIsOnAndUnusable() {
        assertEquals(TOR_BLOCKS_NOSTR, torBlockedNotice(TorAvailability.NO_PROXY_SUPPORT, torRequested = true))
        assertEquals(null, torBlockedNotice(TorAvailability.NO_PROXY_SUPPORT, torRequested = false))
        assertEquals(null, torBlockedNotice(TorAvailability.AVAILABLE, torRequested = true))
        assertEquals(null, torBlockedNotice(TorAvailability.NATIVE_LIBRARY_MISSING, torRequested = true))
    }

    @Test fun onlyTheChannelsThatRideNostrAreToldAboutTheTorGate() {
        // The notice belongs where it bites. On the mesh it is noise: no relay is involved there,
        // and the chat works exactly as it would with Tor off.
        assertTrue(channelUsesNostr(Channel.Location(GeohashChannelLevel.CITY, "9q8yy")))
        assertTrue(channelUsesNostr(Channel.NostrDM("nostr_abcd", "npub1", "9q8yy")))
        assertFalse(channelUsesNostr(Channel.Mesh))
        assertFalse(channelUsesNostr(Channel.NamedChannel("#tech")))
        assertFalse(channelUsesNostr(Channel.MeshDM("peer1", "bob")))
        assertFalse(channelUsesNostr(Channel.Meshtastic()))
    }

    @Test fun theTorNoticeIsOnlyShownOnAChannelTheGateCanBlock() {
        val on = TorAvailability.NO_PROXY_SUPPORT
        assertEquals(TOR_BLOCKS_NOSTR, torBlockedNotice(on, true, Channel.Location(GeohashChannelLevel.CITY, "9q8yy")))
        assertEquals(TOR_BLOCKS_NOSTR, torBlockedNotice(on, true, Channel.NostrDM("nostr_abcd", "npub1", "9q8yy")))
        assertEquals(null, torBlockedNotice(on, true, Channel.Mesh))
        assertEquals(null, torBlockedNotice(on, true, Channel.MeshDM("peer1", "bob")))
        assertEquals(null, torBlockedNotice(on, false, Channel.Location(GeohashChannelLevel.CITY, "9q8yy")))
    }

    private val seen = Instant.fromEpochSeconds(0)

    @Test fun meshChannelsListMeshPeersThenLoRaPeers() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            connectedPeers = listOf("id-bob", "id-alice"),
            peerNicknames = mapOf("id-bob" to "bob", "id-alice" to "alice"),
            peerDirect = mapOf("id-bob" to true),
            favoritePeers = setOf("id-bob"),
            loraPeers = listOf(GeoPerson("!a1b2c3d4", "lora-node", seen)),
        )
        assertEquals(
            listOf(
                PeerEntry("id-alice", "alice", PeerTransport.Routed, favorite = false, unread = true),
                PeerEntry("id-bob", "bob", PeerTransport.Direct, favorite = true),
                PeerEntry("!a1b2c3d4", "lora-node", PeerTransport.LoRa),
            ),
            peerEntries(header, unreadPeers = setOf("id-alice")),
        )
    }

    @Test fun theNamesOfferedToASlashCommandAreThePeersScreensOwn() {
        // Nothing new is plumbed for the completion list: it is the peers list, by name, so it
        // follows the conversation the way that screen does.
        val mesh = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            connectedPeers = listOf("id-bob", "id-alice"),
            peerNicknames = mapOf("id-bob" to "bob#a1b2", "id-alice" to "alice"),
            loraPeers = listOf(GeoPerson("!a1b2c3d4", "lora-node", seen)),
        )
        assertEquals(listOf("alice", "bob#a1b2", "lora-node"), chatPeople(mesh))

        val place = HeaderState(
            selectedLocationChannel = Channel.Location(GeohashChannelLevel.CITY, "9q8yy"),
            geohashPeople = listOf(GeoPerson("npub-dora", "dora", seen)),
        )
        assertEquals(listOf("dora"), chatPeople(place), "a geohash channel offers the people in it")
    }

    @Test fun locationChannelsListTheirPeople() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Location(GeohashChannelLevel.CITY, "9q8yy"),
            // Names here, not ids: MainViewModel fills connectedPeers with display names for location channels.
            connectedPeers = listOf("dora"),
            geohashPeople = listOf(GeoPerson("npub-dora", "dora", seen)),
        )
        assertEquals(listOf(PeerEntry("npub-dora", "dora", PeerTransport.Nostr)), peerEntries(header, unreadPeers = emptySet()))
    }

    @Test fun geohashPeopleWithUnreadDmsAreMarkedThroughTheirConversationKey() {
        val pubkey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        val header = HeaderState(
            selectedLocationChannel = Channel.Location(GeohashChannelLevel.CITY, "9q8yy"),
            geohashPeople = listOf(GeoPerson(pubkey, "dora", seen), GeoPerson("ab".repeat(32), "eve", seen)),
        )
        // DmState.unreadPeers files a geohash DM under the key SaveUserStateAction gives it.
        val unread = setOf("nostr_3bf0c63fcb934634")
        assertEquals(
            listOf(PeerEntry(pubkey, "dora", PeerTransport.Nostr, unread = true), PeerEntry("ab".repeat(32), "eve", PeerTransport.Nostr)),
            peerEntries(header, unread),
        )
    }

    @Test fun aDmLineGoesToTheSelectedChannelOnlyWhenItIsThatDm() {
        val bob = Channel.MeshDM("b0b", "bob")
        val dora = Channel.NostrDM("nostr_3bf0c63fcb934634", "3bf0c63f", "9q8yy", "dora")
        assertEquals(bob, dmChannelFor(HeaderState(selectedPrivatePeer = "b0b", selectedChannel = bob), "b0b"))
        assertEquals(dora, dmChannelFor(HeaderState(selectedPrivatePeer = dora.peerID, selectedChannel = dora), dora.peerID))
        // Another DM, a public chat, or header fields caught mid-update: nowhere to send.
        assertEquals(null, dmChannelFor(HeaderState(selectedPrivatePeer = "b0b", selectedChannel = bob), "m4llory"))
        assertEquals(null, dmChannelFor(HeaderState(selectedPrivatePeer = null, selectedChannel = Channel.Mesh), "b0b"))
        assertEquals(null, dmChannelFor(HeaderState(selectedPrivatePeer = "b0b", selectedChannel = Channel.Mesh), "b0b"))
        assertEquals(null, dmChannelFor(HeaderState(selectedPrivatePeer = "b0b", selectedChannel = dora), "b0b"))
    }

    @Test fun aDmTheViewModelOpenedIsDescribedFromItsChannel() {
        val bob = Channel.MeshDM("b0b", "bob")
        assertEquals(
            PeerEntry("b0b", "bob", PeerTransport.Direct),
            dmPeerFor(HeaderState(selectedPrivatePeer = "b0b", selectedChannel = bob, peerDirect = mapOf("b0b" to true)), "b0b"),
        )
        val dora = Channel.NostrDM("nostr_3bf0c63fcb934634", "3bf0c63fcb93463407af", "9q8yy", "dora")
        val entry = dmPeerFor(HeaderState(selectedPrivatePeer = dora.peerID, selectedChannel = dora), dora.peerID)
        assertEquals(PeerEntry("3bf0c63fcb93463407af", "dora", PeerTransport.Nostr), entry)
        assertEquals(dora.peerID, dmConversationKey(entry))
        // Header fields caught mid-update: named from the nicknames, else the key.
        assertEquals("m4llory", dmPeerFor(HeaderState(peerNicknames = mapOf("id-m" to "m4llory")), "id-m").name)
        assertEquals("0011223344556677".take(12), dmPeerFor(HeaderState(), "0011223344556677").name)
    }
}
