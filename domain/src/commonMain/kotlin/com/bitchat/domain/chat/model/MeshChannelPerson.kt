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
 * [transports] is a private chat whose other side is on neither radio right now. [lastSeen] is the
 * LoRa radio's, so it is set only when LoRa is how the person was found.
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
 * private chat. Minted here and parsed nowhere. [ordinal] tells apart two radio peers that announce
 * the same device id.
 */
fun loRaOnlyPersonId(deviceId: String, ordinal: Int = 0): String =
    "lora-$deviceId" + if (ordinal == 0) "" else "-$ordinal"
