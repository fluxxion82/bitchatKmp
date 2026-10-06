package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.user.UNKNOWN_PEER_NICKNAME
import com.bitchat.domain.user.meshChatName

/**
 * Which connected mesh peer a name typed after a command means (`/msg alice`, `/block alice#1a2b`).
 *
 * A mesh private chat has a name of its own, given when it was opened and kept, and the people list
 * shows its peer under that name. So the typed name is looked up among those names first: it means the
 * peer of the chat called exactly that and nobody else, also while that peer is not connected, when
 * nobody is found. What another peer announces now, under an id that starts alike, must not be able to
 * stand in for a chat the user knows by name. Of two chats with one name, the one opened first is meant.
 *
 * Only a name that is no chat's own is looked up among what the connected peers announce: the peer that
 * announces exactly that, or else the one whose announced name with the suffix of its own id gives that
 * (how a list completes a peer that is about to get a chat), provided that peer announced a name and has
 * no chat under another one. Letter case is ignored throughout, as it always was for these commands.
 */
class FindMeshPeerByName(
    private val chatRepository: ChatRepository,
) : Usecase<String, GeoPerson?> {
    override suspend fun invoke(param: String): GeoPerson? {
        val peers = chatRepository.getMeshPeers()
        val chatNames = chatRepository.getPrivateChatNames().filterKeys { !it.startsWith("nostr_") }

        val chat = chatNames.entries.firstOrNull { (_, name) -> name != null && name.equals(param, ignoreCase = true) }
        if (chat != null) return peers.firstOrNull { it.id == chat.key }

        return peers.firstOrNull { it.displayName.equals(param, ignoreCase = true) }
            ?: peers.firstOrNull { peer ->
                chatNames[peer.id] == null && peer.displayName != UNKNOWN_PEER_NICKNAME &&
                    meshChatName(peer.displayName, peer.id).equals(param, ignoreCase = true)
            }
    }
}
