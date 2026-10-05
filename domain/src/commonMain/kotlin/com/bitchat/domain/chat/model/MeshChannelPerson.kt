package com.bitchat.domain.chat.model

import kotlin.time.Instant

/** How a person on the mesh channel can be reached right now. */
enum class MeshChannelTransport { MESH, LORA }

/**
 * One entry of the mesh channel's people list, as [com.bitchat.domain.chat.ObserveMeshChannelPeople]
 * assembles it: one per device, with every transport it is reachable over at the moment.
 *
 * [id] is the mesh peer id for a connected mesh peer, the conversation key for a private chat, and
 * a separate `lora-` id for a peer known only from the LoRa radio (see [loRaOnlyPersonId]). An empty
 * [transports] is a private chat whose other side is not connected over the mesh right now;
 * [MeshChannelTransport.LORA] beside [MeshChannelTransport.MESH] is a connected peer also heard over
 * the radio. [lastSeen] is the LoRa radio's and is set for a LoRa-only entry.
 */
data class MeshChannelPerson(
    val id: String,
    val displayName: String,
    val transports: Set<MeshChannelTransport>,
    val hasPrivateChat: Boolean,
    val lastSeen: Instant?,
) {
    /** Known only from the LoRa radio: not connected over the mesh and not the other side of a private chat. */
    val isLoRaOnly: Boolean
        get() = !hasPrivateChat && MeshChannelTransport.MESH !in transports
}

/**
 * The id of a person known only from the LoRa radio. A LoRa heartbeat is unauthenticated, so such an
 * entry never takes a mesh peer's id: it must not pick up that peer's favourite, unread marker or
 * private chat. The prefix keeps it apart from every mesh peer id (hex) and conversation key (those,
 * or a `nostr_` key). Minted here and parsed nowhere.
 */
fun loRaOnlyPersonId(deviceId: String): String = "lora-$deviceId"
