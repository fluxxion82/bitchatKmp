package com.bitchat.repo.utils

import com.bitchat.domain.user.UNKNOWN_PEER_NICKNAME
import com.bitchat.domain.user.meshChatNameSuffix
import com.bitchat.domain.user.sanitizedMeshNickname

/**
 * What [raw], the nickname kept for a mesh peer, says the peer is called, or null when it says nothing:
 * nothing is left of it, or it is the placeholder of a peer that announced no name.
 */
internal fun announcedMeshName(raw: String?): String? =
    sanitizedMeshNickname(raw)?.takeUnless { it == UNKNOWN_PEER_NICKNAME }

/**
 * What [raw] says the mesh peer [peerID] is called when it is a name this app showed for the peer and
 * was handed back with a request to write to it, or null when it says nothing.
 *
 * What the app shows is not always a name the peer has: a chat's own name comes back with the suffix
 * the app gave it, which is taken off again, and a peer without a name is listed under the start of
 * its id, which is no name at all.
 */
internal fun meshNameHandedBack(raw: String?, peerID: String): String? =
    // The suffix comes off before anything is cut to length: a name of full length plus its suffix
    // would otherwise lose part of the suffix to the cut and keep the rest as if it were name.
    announcedMeshName(raw?.trim()?.removeSuffix(meshChatNameSuffix(peerID)))
        ?.takeUnless { it.length >= ID_PREFIX_SHOWN_AS_NAME && peerID.startsWith(it, ignoreCase = true) }

/** The shortest start of a peer id that is taken for the id rather than for a name. */
private const val ID_PREFIX_SHOWN_AS_NAME = 8
