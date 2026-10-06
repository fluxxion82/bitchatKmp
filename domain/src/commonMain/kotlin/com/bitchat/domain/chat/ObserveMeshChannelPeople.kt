package com.bitchat.domain.chat

import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.LoRaPerson
import com.bitchat.domain.chat.model.MeshChannelPerson
import com.bitchat.domain.chat.model.MeshChannelTransport
import com.bitchat.domain.chat.model.loRaOnlyPersonId
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.user.UNKNOWN_PEER_NICKNAME
import com.bitchat.domain.user.meshChatName
import com.bitchat.domain.user.repository.BlockListRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.withIndex

/**
 * The people of the mesh channel: the connected mesh peers, the other sides of private chats and
 * the peers heard over LoRa, put into one list here and nowhere else.
 *
 * Nothing on either transport authenticates a sender, which decides what may be merged:
 * - A LoRa peer is folded into a **connected mesh peer** that has the device id it announces. That
 *   entry keeps the mesh peer's name and gains [MeshChannelTransport.LORA]. The connection is a live
 *   fact that ends by itself, and the radio peer is back as its own entry when it does.
 * - A LoRa peer is **never** folded into a private chat, whatever id and name it announces. A private
 *   chat is a record a packet left behind, and both the name in a chat and the name in a heartbeat
 *   are whatever their senders chose: their being equal proves nothing. The chat and the radio peer
 *   are two entries, a duplicate rather than a device shown under a name someone else supplied.
 * - A peer of a LoRa stack whose ids are not mesh ids is always its own entry.
 *
 * A mesh private chat has a name of its own, given when the chat was opened and never changed by what
 * is announced afterwards (an announcement is not authenticated; the chat is). A connected peer that
 * has such a chat is listed under the chat's name, with what it announces now beside it when that
 * differs ([MeshChannelPerson.claimedName]). A connected peer without one is listed under what it
 * announces now.
 *
 * Mesh peers and private chats keep the keys the repository gives them and are compared exactly. A
 * blocked mesh user is in none of the three sources: the repository leaves it out of the mesh peers
 * and the LoRa peers, and its private chat is left out here.
 *
 * Refreshes run one after another, so the last emission reflects the latest inputs, and each reads the
 * mesh peers, the LoRa peers and the block list anew, so the block list is applied to every source at
 * the same moment. The private chats' names are read again only after the chats changed, and no read
 * copies a chat's history: a radio heartbeat costs nothing per message.
 */
class ObserveMeshChannelPeople(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
    private val blockListRepository: BlockListRepository,
) {
    operator fun invoke(): Flow<List<MeshChannelPerson>> = channelFlow {
        // The bus replays its latest event to a new subscriber. Treating that first event as a change
        // of everything closes the gap between the first read and the subscription: what changed in
        // it is picked up by one more read instead of waiting for the next relevant event.
        val chatChanges = chatEventBus.events()
            .withIndex()
            .mapNotNull { (index, event) ->
                when {
                    index == 0 || event == ChatEvent.PrivateChatsUpdated -> Change.CHATS
                    event == ChatEvent.MeshPeersUpdated -> Change.PEERS
                    else -> null
                }
            }

        var chatNames = emptyMap<String, String?>()
        var chatNamesAreStale = true

        merge(
            chatChanges,
            chatRepository.observeLoRaPeerChanges().map { Change.PEERS },
            blockListRepository.observeBlockList().map { Change.PEERS },
        ).onStart { emit(Change.CHATS) }.collect { change ->
            if (change == Change.CHATS) chatNamesAreStale = true
            try {
                if (chatNamesAreStale) {
                    chatNames = chatRepository.getPrivateChatNames()
                    chatNamesAreStale = false
                }
                // The block list stores its identifiers in lower case and matches without regard to case.
                val blocked = blockListRepository.getMeshBlockedUsers().mapTo(HashSet()) { it.identifier.lowercase() }
                send(
                    mergePeople(
                        meshPeople = chatRepository.getMeshPeers(),
                        chatNames = chatNames.filterKeys { it.lowercase() !in blocked },
                        loRaPeople = chatRepository.getLoRaPeers(),
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (collision: RuntimeException) {
                // The repository's chats are written from other coroutines without a lock, and a read
                // that runs into one of those writes can fail in more than one way. This refresh is
                // given up, not repeated: the names stay marked stale, and the change event that write
                // ends with brings the next one. One failed read must not end the people list.
                println("ObserveMeshChannelPeople: refresh skipped (${collision::class.simpleName})")
            }
        }
    }.distinctUntilChanged() // Several triggers can describe one change; the same list is not emitted twice.

    private enum class Change { CHATS, PEERS }

    private fun mergePeople(
        meshPeople: List<GeoPerson>,
        chatNames: Map<String, String?>,
        loRaPeople: List<LoRaPerson>,
    ): List<MeshChannelPerson> {
        val listed = LinkedHashMap<String, MeshChannelPerson>()
        // A radio peer announces a mesh id as hex, in whatever letter case its stack prints.
        val connectedKeys = HashMap<String, String>()

        for (person in meshPeople) {
            if (person.id in listed) continue
            listed[person.id] = MeshChannelPerson(
                id = person.id,
                displayName = person.displayName,
                transports = setOf(MeshChannelTransport.MESH),
                hasPrivateChat = false,
                lastSeen = null,
            )
            if (person.id.lowercase() !in connectedKeys) connectedKeys[person.id.lowercase()] = person.id
        }

        for ((key, name) in chatNames) {
            val connected = listed[key]
            // Only a mesh chat has a name of its own. A Nostr chat is still called after the sender of its
            // newest message, and the header lets what it already knows overrule that.
            val fixedName = name.takeIf { !key.startsWith("nostr_") }
            listed[key] = when {
                connected == null -> MeshChannelPerson(
                    id = key,
                    displayName = name ?: key.take(12),
                    transports = emptySet(),
                    hasPrivateChat = true,
                    lastSeen = null,
                    nameIsFixed = fixedName != null,
                )

                fixedName == null -> connected.copy(hasPrivateChat = true)

                else -> connected.copy(
                    displayName = fixedName,
                    hasPrivateChat = true,
                    nameIsFixed = true,
                    // The chat's name is the name it was opened under with the id's suffix, and a peer
                    // that announces no name is listed under the placeholder: neither is another name.
                    claimedName = connected.displayName.takeUnless { announced ->
                        announced == fixedName || meshChatName(announced, key) == fixedName || announced == UNKNOWN_PEER_NICKNAME
                    },
                )
            }
        }

        val takenIds = HashSet(listed.keys)
        val loRaOnly = ArrayList<MeshChannelPerson>()
        for (person in loRaPeople) {
            val connectedKey = person.meshDeviceId?.let { connectedKeys[it.lowercase()] }
            if (connectedKey != null) {
                val connected = listed.getValue(connectedKey)
                listed[connectedKey] = connected.copy(transports = connected.transports + MeshChannelTransport.LORA)
                continue
            }
            // The id cannot be taken: mesh peer ids are hex and conversation keys are those or `nostr_`
            // keys, so none starts with the LoRa prefix, and a radio lists a device id once. Should it
            // happen all the same, the first holder keeps the id; no other id is invented, because a
            // made-up id could be saved with a favourite and claimed by a later peer.
            val id = loRaOnlyPersonId(person.id)
            if (!takenIds.add(id)) continue
            loRaOnly += MeshChannelPerson(
                id = id,
                displayName = person.displayName,
                transports = setOf(MeshChannelTransport.LORA),
                hasPrivateChat = false,
                lastSeen = person.lastSeen,
            )
        }

        return listed.values + loRaOnly
    }
}
