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

    @Test fun loraDmPeerIdsOnlyContainsRadioOnlyPeopleThatCanOpenAMeshDm() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            meshPeople = listOf(
                MeshChannelPerson("lora-mesh", "radio", setOf(MeshChannelTransport.LORA), false, seen, dmPeerId = "a1b2"),
                MeshChannelPerson("lora-foreign", "foreign", setOf(MeshChannelTransport.LORA), false, seen),
                MeshChannelPerson("private", "private", emptySet(), true, null, dmPeerId = "private"),
            ),
        )

        assertEquals(mapOf("lora-mesh" to "a1b2"), header.loraDmPeerIds)
    }

    @Test fun namesFollowThePrecedenceTheListAndTheDirectoryAlwaysHad() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            nicknameDirectory = mapOf(
                "connected" to "announced earlier",
                "nostr_known" to "alice",
                "other" to "bob",
            ),
            meshPeople = listOf(
                MeshChannelPerson("connected", "announced now", setOf(MeshChannelTransport.MESH), false, null),
                // A later message under a known conversation supplies another name: the known one stays.
                MeshChannelPerson("nostr_known", "mallory", emptySet(), true, null),
                // Nothing known for this conversation yet: its own name fills the gap.
                MeshChannelPerson("fresh", "carol", emptySet(), true, null),
                MeshChannelPerson("lora-radio", "radio", setOf(MeshChannelTransport.LORA), false, seen),
            ),
        )

        assertEquals(
            mapOf(
                "connected" to "announced now",
                "nostr_known" to "alice",
                "fresh" to "carol",
                "other" to "bob",
            ),
            header.peerNicknames,
        )
        assertEquals(
            header.nicknameDirectory,
            header.copy(selectedLocationChannel = Channel.Location(com.bitchat.domain.location.model.GeohashChannelLevel.CITY, "9q8yy")).peerNicknames,
        )
    }

    @Test fun aFixedNameOfAnOfflineChatWinsOverTheDirectory() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            nicknameDirectory = mapOf("offline" to "announced before it left"),
            meshPeople = listOf(
                MeshChannelPerson("offline", "alice#1a2b", emptySet(), true, null, nameIsFixed = true),
            ),
        )

        assertEquals(mapOf("offline" to "alice#1a2b"), header.peerNicknames)
    }

    @Test fun fixedPrivateNamesWinOverTheDirectoryAndLiveConnectedNames() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            nicknameDirectory = mapOf("fixed" to "directory", "offline" to "directory offline"),
            meshPeople = listOf(
                MeshChannelPerson("fixed", "alice#1a2b", setOf(MeshChannelTransport.MESH), true, null, nameIsFixed = true),
                MeshChannelPerson("live", "announced now", setOf(MeshChannelTransport.MESH), false, null),
                MeshChannelPerson("offline", "old live", emptySet(), true, null),
            ),
        )

        assertEquals(
            mapOf("offline" to "directory offline", "fixed" to "alice#1a2b", "live" to "announced now"),
            header.peerNicknames,
        )
    }

    @Test fun claimedNamesOnlyListCurrentMeshAnnouncements() {
        val header = HeaderState(
            selectedLocationChannel = Channel.Mesh,
            meshPeople = listOf(
                MeshChannelPerson("fixed", "alice#1a2b", setOf(MeshChannelTransport.MESH), true, null, nameIsFixed = true, claimedName = "bob"),
                MeshChannelPerson("unchanged", "carol", setOf(MeshChannelTransport.MESH), true, null, nameIsFixed = true),
                MeshChannelPerson("live", "dave", setOf(MeshChannelTransport.MESH), false, null),
            ),
        )

        assertEquals(mapOf("fixed" to "bob"), header.claimedNames)
        assertEquals(setOf("fixed", "unchanged"), header.fixedNamePeers)
        assertEquals(
            emptySet(),
            header.copy(selectedLocationChannel = Channel.Location(com.bitchat.domain.location.model.GeohashChannelLevel.CITY, "9q8yy")).fixedNamePeers,
        )
        assertEquals(
            emptyMap(),
            header.copy(selectedLocationChannel = Channel.Location(com.bitchat.domain.location.model.GeohashChannelLevel.CITY, "9q8yy")).claimedNames,
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
