package com.bitchat.domain.chat

import com.bitchat.domain.chat.eventbus.ChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.LoRaPerson
import com.bitchat.domain.chat.model.MeshChannelPerson
import com.bitchat.domain.chat.model.MeshChannelTransport
import com.bitchat.domain.chat.model.loRaOnlyPersonId
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.user.repository.BlockListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.withIndex

/**
 * The people of the mesh channel, one entry per device: the connected mesh peers, the other sides
 * of private chats and the peers heard over LoRa, merged here and nowhere else.
 *
 * Nothing on either transport authenticates a sender, so the merge may only ever fold a LoRa peer
 * into an entry of the same emitted list:
 * - into a **connected** mesh peer with the same device id, which keeps the mesh peer's name (a live
 *   fact that ends when the connection does);
 * - into a **private chat** with the same device id only when the names are equal, so nothing is
 *   hidden. A private chat is a record a packet leaves behind: with a different name the LoRa peer
 *   stays its own entry, a duplicate rather than a device shown under a name someone else supplied.
 * A peer of a LoRa stack whose ids are not mesh ids is always its own entry.
 *
 * A blocked mesh user is in none of the three sources: the repository leaves it out of the mesh
 * peers and the LoRa peers, and its private chat is left out here.
 *
 * Every emission is one fresh read of the repository; there is no second record of who is connected
 * or listed. Refreshes run one after another, so the last emission reflects the latest inputs.
 */
class ObserveMeshChannelPeople(
    private val chatRepository: ChatRepository,
    private val chatEventBus: ChatEventBus,
    private val blockListRepository: BlockListRepository,
) {
    operator fun invoke(): Flow<List<MeshChannelPerson>> = channelFlow {
        // The bus replays its latest event to a new subscriber. Taking that first event whatever its
        // kind closes the gap between the first read and the subscription: a change that fell into
        // it is picked up by one more read instead of waiting for the next relevant event.
        val chatChanges = chatEventBus.events()
            .withIndex()
            .filter { (index, event) ->
                index == 0 || event == ChatEvent.MeshPeersUpdated || event == ChatEvent.PrivateChatsUpdated
            }
            .map { }

        // The LoRa flow and the block list only say that something changed; the lists themselves
        // are read together, so the block list is applied to all three sources at the same moment.
        merge(
            chatChanges,
            chatRepository.observeLoRaPeers().map { },
            blockListRepository.observeBlockList().map { },
        ).onStart { emit(Unit) }.collect {
            send(
                mergePeople(
                    meshPeople = chatRepository.getMeshPeers(),
                    privateChats = chatRepository.getPrivateChats()
                        .filterKeys { !blockListRepository.isMeshUserBlocked(it) },
                    loRaPeople = chatRepository.getLoRaPeers(),
                ),
            )
        }
    }.distinctUntilChanged() // Several triggers can describe one change; the same list is not emitted twice.

    private fun mergePeople(
        meshPeople: List<GeoPerson>,
        privateChats: Map<String, List<BitchatMessage>>,
        loRaPeople: List<LoRaPerson>,
    ): List<MeshChannelPerson> {
        val people = ArrayList<MeshChannelPerson>()

        fun indexOf(normalizedId: String): Int = people.indexOfFirst { normalize(it.id) == normalizedId }

        for (person in meshPeople) {
            if (indexOf(normalize(person.id)) < 0) {
                people += MeshChannelPerson(
                    id = person.id,
                    displayName = person.displayName,
                    transports = setOf(MeshChannelTransport.MESH),
                    hasPrivateChat = false,
                    lastSeen = null,
                )
            }
        }

        for ((id, messages) in privateChats) {
            val index = indexOf(normalize(id))
            if (index >= 0) {
                people[index] = people[index].copy(hasPrivateChat = true)
            } else {
                people += MeshChannelPerson(
                    id = id,
                    displayName = messages.lastOrNull { it.senderPeerID == id }?.sender ?: id.take(12),
                    transports = emptySet(),
                    hasPrivateChat = true,
                    lastSeen = null,
                )
            }
        }

        for (person in loRaPeople) {
            val matchingIndex = person.meshDeviceId?.let { indexOf(normalize(it)) } ?: -1
            if (matchingIndex >= 0) {
                val existing = people[matchingIndex]
                if (MeshChannelTransport.MESH in existing.transports || existing.displayName == person.displayName) {
                    people[matchingIndex] = existing.copy(
                        transports = existing.transports + MeshChannelTransport.LORA,
                        lastSeen = existing.lastSeen ?: person.lastSeen,
                    )
                    continue
                }
            }
            people += MeshChannelPerson(
                id = nextLoRaOnlyPersonId(person.id, people),
                displayName = person.displayName,
                transports = setOf(MeshChannelTransport.LORA),
                hasPrivateChat = false,
                lastSeen = person.lastSeen,
            )
        }

        return people
    }

    private fun nextLoRaOnlyPersonId(deviceId: String, people: List<MeshChannelPerson>): String {
        var ordinal = 0
        while (true) {
            val id = loRaOnlyPersonId(deviceId, ordinal)
            if (people.none { normalize(it.id) == normalize(id) }) return id
            ordinal += 1
        }
    }

    private fun normalize(id: String): String = id.trim().lowercase()
}
