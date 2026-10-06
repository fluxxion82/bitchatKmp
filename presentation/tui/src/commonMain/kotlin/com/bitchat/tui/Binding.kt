package com.bitchat.tui

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.failure.CommandFailure
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.Note
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.viewvo.chat.HeaderState
import kotlin.time.Instant

// Pure helpers for the binding layer (presentation/tui/binding), which maps view-model state onto these
// screens. They take viewvo and domain types only, so they are tested here on the JVM.

/** What the chat shows for a failed slash command; the same wording as the Compose app. */
fun commandFailureMessage(failure: CommandFailure): String = when (failure) {
    CommandFailure.MissingTarget -> "command requires a target"
    CommandFailure.RequiresChannel -> "command requires a channel"
    CommandFailure.RequiresLocation -> "command only available in location channels"
    CommandFailure.RequiresMesh -> "command only available from mesh channel"
    CommandFailure.RequiresNamedChannel -> "command only available in named channels"
    is CommandFailure.Unknown -> failure.reason.ifBlank { "invalid command" }
}

/**
 * The people list for the current channel: in a location channel its geohash people (Nostr); on
 * the mesh the mesh peers (sorted as in the Compose sidebar), then the LoRa peers. [unreadPeers]
 * are the peers with unread DMs (`DmState.unreadPeers`).
 */
fun peerEntries(header: HeaderState, unreadPeers: Set<String>): List<PeerEntry> =
    if (header.selectedLocationChannel is Channel.Location) {
        geoPeerEntries(header.geohashPeople, PeerTransport.Nostr, header.favoritePeers, unreadPeers)
    } else if (header.isMeshChannel) {
        meshChannelPeerEntries(header.meshPeople, header.peerNicknames, header.favoritePeers, unreadPeers)
    } else {
        legacyMeshPeerEntries(header.connectedPeers, header.peerNicknames, header.peerDirect, header.favoritePeers, unreadPeers) +
            geoPeerEntries(header.loraPeers, PeerTransport.LoRa, header.favoritePeers, unreadPeers)
    }

/** What the header bar shows; it must equal `peerEntries(header, ...).size`. */
fun peopleCount(header: HeaderState): Int = when {
    header.selectedLocationChannel is Channel.Location -> header.geohashPeople.size
    header.isMeshChannel -> header.meshPeople.size
    else -> header.connectedPeers.size + header.loraPeers.size
}

/**
 * The people a nickname can be completed to in the current chat: the same list the peers screen
 * shows, by display name, so `/hug ` offers exactly who is on screen. Names may carry the `#abcd`
 * suffix that tells two people of one nickname apart, which is what makes them worth completing.
 */
fun chatPeople(header: HeaderState): List<String> = peerEntries(header, emptySet()).map { it.name }

/**
 * Where a DM line for the conversation [key] goes: the view model's selected channel, only while
 * it is that DM (a mesh or geohash DM whose peer is [key], selected as the private peer [key]).
 * Null otherwise, and then nothing is sent: never to whatever chat happens to be active.
 */
fun dmChannelFor(header: HeaderState, key: String): Channel? {
    if (header.selectedPrivatePeer != key) return null
    return when (val channel = header.selectedChannel) {
        is Channel.MeshDM -> channel.takeIf { it.peerID == key }
        is Channel.NostrDM -> channel.takeIf { it.peerID == key }
        else -> null
    }
}

/**
 * The peer a DM is shown as when the view model opened it by itself ([key]: its conversation key,
 * as after `/msg bob`): from its channel when the header has caught up, else from the nicknames
 * or the key. For a geohash DM the entry carries the full key, so [dmConversationKey] gives [key] back.
 */
fun dmPeerFor(header: HeaderState, key: String): PeerEntry {
    val channel = header.selectedChannel
    val person = header.meshPeople.firstOrNull { it.id == key }
    val route = person?.transport()
        ?: if (header.peerDirect[key] == true) PeerTransport.Direct else PeerTransport.Routed
    return when {
        channel is Channel.NostrDM && channel.peerID == key ->
            PeerEntry(channel.fullPubkey, channel.displayName ?: key, PeerTransport.Nostr)
        channel is Channel.MeshDM && channel.peerID == key ->
            PeerEntry(
                key,
                person?.takeIf { it.nameIsFixed }?.displayName ?: channel.displayName ?: person?.displayName ?: header.peerNicknames[key] ?: key.take(12),
                route,
                claims = person?.claimedName,
            )
        else -> PeerEntry(key, person?.displayName ?: header.peerNicknames[key] ?: key.take(12), if (key.startsWith("nostr_")) PeerTransport.Nostr else route)
    }
}

/**
 * Sends [line] through [send], which answers whether it took it, and puts the line back in
 * [editor] when it did not. The prompt clears the editor on `Enter`, before anything has had a
 * chance to refuse the line, so a refused one has to be given back or it is lost. Why it was
 * refused is shown by whoever refused it (a chat's `errorMessage`).
 */
fun sendOrKeep(editor: LineEditor, line: String, send: () -> Boolean) {
    if (!send()) editor.insert(line)
}

/**
 * The notes of a place as a conversation, so the chat screen can draw them: a note's author is
 * its [Note.displayName] (nickname and the last four characters of the key, as the Compose app
 * shows it) and its key is what colours them, the same way a message's sender is coloured.
 *
 * Oldest first, whatever order they arrive in: a chat is anchored on its last line, so notes the
 * relay hands back newest-first would open on the oldest ones with the newest out of sight.
 */
fun noteMessages(notes: List<Note>): List<BitchatMessage> = notes.sortedBy { it.createdAt }.map { note ->
    BitchatMessage(
        id = note.id,
        sender = note.displayName,
        content = note.content,
        timestamp = Instant.fromEpochSeconds(note.createdAt.toLong()),
        senderPeerID = note.pubkey,
    )
}

/**
 * What a chat that rides Nostr says while Tor is on and this build cannot carry it: nothing reaches
 * a relay until Tor is switched off, and nothing switches it off on the user's behalf (see
 * `torGateAllowsTraffic`). Null when there is nothing to say.
 *
 * [channel] decides where it is worth saying. The mesh and the channels carried over it never touch
 * a relay, so the notice is noise on those screens; only a geohash channel and a DM that routes
 * over Nostr are actually blocked by the gate.
 */
fun torBlockedNotice(availability: TorAvailability, torRequested: Boolean, channel: Channel): String? =
    if (channelUsesNostr(channel)) torBlockedNotice(availability, torRequested) else null

/** The gate itself, without a channel: true wherever the build cannot carry Tor and Tor is on. */
fun torBlockedNotice(availability: TorAvailability, torRequested: Boolean): String? =
    if (torRequested && availability == TorAvailability.NO_PROXY_SUPPORT) TOR_BLOCKS_NOSTR else null

/**
 * Whether [channel] reaches its people through a Nostr relay. Mesh chats, the named channels
 * carried over the mesh, mesh DMs and Meshtastic are all radio, and the Tor gate cannot touch them.
 */
fun channelUsesNostr(channel: Channel): Boolean = when (channel) {
    is Channel.Location, is Channel.NostrDM -> true
    Channel.Mesh, is Channel.NamedChannel, is Channel.MeshDM, is Channel.Meshtastic -> false
}

/** Short enough to survive a narrow screen, and it says where to go and what it buys. */
const val TOR_BLOCKS_NOSTR = "Tor is on but cannot run here, so nothing connects. Turn Tor off in Settings."

/**
 * Forgets what was typed into [editors], for the emergency wipe: a prompt holds an unsent draft
 * and the lines already sent, and the wipe is meant to leave nothing of the old identity behind.
 */
fun eraseDrafts(vararg editors: LineEditor) = editors.forEach { it.reset() }
