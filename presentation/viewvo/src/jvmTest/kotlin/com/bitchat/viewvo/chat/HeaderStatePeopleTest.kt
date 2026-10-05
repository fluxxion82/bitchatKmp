package com.bitchat.viewvo.chat

import com.bitchat.domain.chat.model.MeshChannelPerson
import com.bitchat.domain.chat.model.MeshChannelTransport
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class HeaderStatePeopleTest {
    private val seen = Instant.fromEpochSeconds(0)

    @Test fun meshChannelPropertiesDeriveFromOnePeopleList() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            meshPeople = listOf(
                MeshChannelPerson("mesh", "mesh", setOf(MeshChannelTransport.MESH), false, null),
                MeshChannelPerson("both", "both", setOf(MeshChannelTransport.MESH, MeshChannelTransport.LORA), false, null),
                MeshChannelPerson("private", "private", emptySet(), true, null),
                MeshChannelPerson("lora-radio", "radio", setOf(MeshChannelTransport.LORA), false, seen),
            ),
        )

        assertEquals(true, header.isMeshChannel)
        assertEquals(listOf("mesh", "both", "private"), header.connectedPeers)
        assertEquals(mapOf("mesh" to true, "both" to true), header.peerDirect)
        assertEquals(listOf(GeoPerson("lora-radio", "radio", seen)), header.loraPeers)
    }

    @Test fun namesFollowThePrecedenceTheListAndTheDirectoryAlwaysHad() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            nicknameDirectory = mapOf(
                "connected" to "announced earlier",
                "nostr_known" to "alice",
                "folded" to "mallory",
                "other" to "bob",
            ),
            meshPeople = listOf(
                MeshChannelPerson("connected", "announced now", setOf(MeshChannelTransport.MESH), false, null),
                // A later message under a known conversation supplies another name: the known one stays.
                MeshChannelPerson("nostr_known", "mallory", emptySet(), true, null),
                // Nothing known for this conversation yet: its own name fills the gap.
                MeshChannelPerson("fresh", "carol", emptySet(), true, null),
                // A radio peer was folded into this chat because both are called "radio alice": the row
                // must show that name, not the one an earlier message left in the directory.
                MeshChannelPerson("folded", "radio alice", setOf(MeshChannelTransport.LORA), true, seen),
                MeshChannelPerson("lora-radio", "radio", setOf(MeshChannelTransport.LORA), false, seen),
            ),
        )

        assertEquals(
            mapOf(
                "connected" to "announced now",
                "nostr_known" to "alice",
                "fresh" to "carol",
                "folded" to "radio alice",
                "other" to "bob",
            ),
            header.peerNicknames,
        )
        assertEquals(
            header.nicknameDirectory,
            header.copy(selectedLocationChannel = Channel.Location(com.bitchat.domain.location.model.GeohashChannelLevel.CITY, "9q8yy")).peerNicknames,
        )
    }

    @Test fun locationChannelPropertiesKeepTheirExistingFormulas() {
        val people = listOf(GeoPerson("npub", "dora", seen))
        val header = HeaderState(
            selectedLocationChannel = Channel.Location(com.bitchat.domain.location.model.GeohashChannelLevel.CITY, "9q8yy"),
            geohashPeople = people,
            meshPeople = listOf(MeshChannelPerson("lora", "radio", setOf(MeshChannelTransport.LORA), false, seen)),
        )

        assertEquals(false, header.isMeshChannel)
        assertEquals(listOf("dora"), header.connectedPeers)
        assertEquals(mapOf("npub" to false), header.peerDirect)
        assertEquals(listOf(GeoPerson("lora", "radio", seen)), header.loraPeers)
    }
}
