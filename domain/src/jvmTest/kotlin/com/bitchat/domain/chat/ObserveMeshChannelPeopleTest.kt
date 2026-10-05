package com.bitchat.domain.chat

import app.cash.turbine.test
import com.bitchat.domain.base.defaultContextFacade
import com.bitchat.domain.chat.eventbus.InMemoryChatEventBus
import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChatEvent
import com.bitchat.domain.chat.model.LoRaPerson
import com.bitchat.domain.chat.model.MeshChannelPerson
import com.bitchat.domain.chat.model.MeshChannelTransport
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.user.model.BlockedUser
import com.bitchat.domain.user.repository.BlockListRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ObserveMeshChannelPeopleTest {
    private val seen = Instant.fromEpochSeconds(0)

    @Test fun mergesMeshAndBitchatLoRaPeopleOnce() = runTest {
        val fixture = Fixture(
            meshPeople = listOf(person("X", "mesh name")),
            initialLoRaPeople = listOf(lora("X", "radio name", meshDeviceId = "x")),
        )

        fixture.observe().test {
            val result = awaitItem()
            assertEquals(1, result.count { it.id.equals("X", ignoreCase = true) })
            assertEquals(
                setOf(MeshChannelTransport.MESH, MeshChannelTransport.LORA),
                result.single { it.id.equals("X", ignoreCase = true) }.transports,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun foldsPrivateAndLoRaPeopleOnlyWhenTheirNamesMatch() = runTest {
        val fixture = Fixture(
            meshPeople = listOf(person("X", "mesh name")),
            chats = linkedMapOf("X" to listOf(message("X", "radio name"))),
            initialLoRaPeople = listOf(lora("x", "radio name", meshDeviceId = "X")),
        )

        fixture.observe().test {
            awaitItem()
            fixture.meshPeople = emptyList()
            fixture.events.update(ChatEvent.MeshPeersUpdated)
            assertEquals(
                listOf(
                    MeshChannelPerson(
                        id = "X",
                        displayName = "radio name",
                        transports = setOf(MeshChannelTransport.LORA),
                        hasPrivateChat = true,
                        lastSeen = seen,
                    ),
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun keepsMeshLoRaAndPrivateOnlyPeople() = runTest {
        val fixture = Fixture(
            meshPeople = listOf(person("M", "mesh")),
            chats = linkedMapOf("P" to emptyList()),
            initialLoRaPeople = listOf(lora("L", "radio", null)),
        )

        fixture.observe().test {
            assertEquals(
                listOf(
                    MeshChannelPerson("M", "mesh", setOf(MeshChannelTransport.MESH), false, null),
                    MeshChannelPerson("P", "P", emptySet(), true, null),
                    MeshChannelPerson("lora-L", "radio", setOf(MeshChannelTransport.LORA), false, seen),
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun privatePersonUsesTheLatestMessageForItsConversationKey() = runTest {
        val fixture = Fixture(
            chats = linkedMapOf(
                "P" to listOf(message("other", "other name"), message("P", "private name")),
            ),
        )

        fixture.observe().test {
            assertEquals("private name", awaitItem().single().displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun spoofedPrivateNameLeavesTheLoRaPersonVisibleAfterLeave() = runTest {
        val fixture = Fixture(
            meshPeople = listOf(person("X", "mesh name")),
            chats = linkedMapOf("X" to listOf(message("X", "spoofed name"))),
            initialLoRaPeople = listOf(lora("X", "radio name", meshDeviceId = "X")),
        )

        fixture.observe().test {
            awaitItem()
            fixture.meshPeople = emptyList()
            fixture.events.update(ChatEvent.MeshPeersUpdated)
            assertEquals(
                listOf(
                    MeshChannelPerson("X", "spoofed name", emptySet(), true, null),
                    MeshChannelPerson("lora-X", "radio name", setOf(MeshChannelTransport.LORA), false, seen),
                ),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun foreignLoRaIdentityRemainsDistinctAndIdsAreUnique() = runTest {
        val fixture = Fixture(
            meshPeople = listOf(person("X", "mesh name")),
            initialLoRaPeople = listOf(lora("X", "foreign name", meshDeviceId = null)),
        )

        fixture.observe().test {
            val result = awaitItem()
            assertEquals(listOf("X", "lora-X"), result.map { it.id })
            assertEquals(result.size, result.map { it.id }.toSet().size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun keepsMeshThenPrivateThenLoRaOrder() = runTest {
        val fixture = Fixture(
            meshPeople = listOf(person("M2", "two"), person("M1", "one")),
            chats = linkedMapOf("P2" to emptyList(), "P1" to emptyList()),
            initialLoRaPeople = listOf(lora("L2", "two", null), lora("L1", "one", null)),
        )

        fixture.observe().test {
            assertEquals(listOf("M2", "M1", "P2", "P1", "lora-L2", "lora-L1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun eachSourceTriggersAFreshMerge() = runTest {
        val fixture = Fixture(meshPeople = listOf(person("M", "mesh")))

        fixture.observe().test {
            assertEquals(listOf("M"), awaitItem().map { it.id })
            fixture.loRaPeople.value = listOf(lora("L", "radio", null))
            assertEquals(listOf("M", "lora-L"), awaitItem().map { it.id })
            fixture.chats = linkedMapOf("P" to listOf(message("P", "private")))
            fixture.events.update(ChatEvent.PrivateChatsUpdated)
            assertEquals(listOf("M", "P", "lora-L"), awaitItem().map { it.id })
            fixture.meshPeople = listOf(person("N", "next"))
            fixture.events.update(ChatEvent.MeshPeersUpdated)
            assertEquals(listOf("N", "P", "lora-L"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun aRefreshReadsTheLoRaPeopleAsTheyAreNow() = runTest {
        val fixture = Fixture(initialLoRaPeople = listOf(lora("X", "radio", meshDeviceId = "X")))

        fixture.observe().test {
            assertEquals(listOf("lora-X"), awaitItem().map { it.id })
            // X is blocked: the repository stops reporting it, but no heartbeat has come to say so.
            fixture.loRaPeopleNow = emptyList()
            fixture.meshPeople = listOf(person("M", "mesh"))
            fixture.events.update(ChatEvent.MeshPeersUpdated)
            assertEquals(listOf("M"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun aChangeBetweenTheFirstReadAndTheSubscriptionIsNotLost() = runTest {
        val fixture = Fixture()
        // The mesh peer connects after the first read; its event is then overwritten on the bus by an
        // unrelated one before the use case has subscribed, so only that unrelated event is replayed.
        // The radio stays silent, so nothing else can cause the second read.
        fixture.loRaChanges = emptyFlow()
        fixture.meshPeopleAfterFirstRead = listOf(person("X", "mesh"))
        fixture.events.update(ChatEvent.MessageReceived)

        fixture.observe().test {
            assertEquals(emptyList(), awaitItem())
            assertEquals(listOf("X"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun aBlockedUsersPrivateChatIsNotListedAndBlockingRefreshesTheList() = runTest {
        val fixture = Fixture(
            meshPeople = listOf(person("M", "mesh")),
            chats = linkedMapOf("X" to listOf(message("X", "blocked soon")), "P" to listOf(message("P", "private"))),
        )

        fixture.observe().test {
            assertEquals(listOf("M", "X", "P"), awaitItem().map { it.id })
            fixture.blocked += "X"
            fixture.blockListChanges.emit(emptyList())
            assertEquals(listOf("M", "P"), awaitItem().map { it.id })
            // A later mesh update must not bring the blocked user's chat back.
            fixture.meshPeople = listOf(person("M", "mesh"), person("N", "next"))
            fixture.events.update(ChatEvent.MeshPeersUpdated)
            assertEquals(listOf("M", "N", "P"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class Fixture(
        var meshPeople: List<GeoPerson> = emptyList(),
        var chats: Map<String, List<BitchatMessage>> = emptyMap(),
        initialLoRaPeople: List<LoRaPerson> = emptyList(),
    ) {
        val loRaPeople = MutableStateFlow(initialLoRaPeople)
        val events = InMemoryChatEventBus(defaultContextFacade)

        /** What tells the use case that the radio's peers changed; the state flow itself unless a test silences it. */
        var loRaChanges: Flow<List<LoRaPerson>> = loRaPeople

        /** What the repository answers with instead of the flow's value: a block applied since the last heartbeat. */
        var loRaPeopleNow: List<LoRaPerson>? = null

        /** Mesh peers that connect right after the first read, before the event subscription exists. */
        var meshPeopleAfterFirstRead: List<GeoPerson>? = null
        private var meshReads = 0

        private val repository = object : ChatRepository by mockk(relaxed = true) {
            override suspend fun getMeshPeers(): List<GeoPerson> {
                val late = meshPeopleAfterFirstRead
                return if (late != null && meshReads++ > 0) late else meshPeople
            }
            override suspend fun getPrivateChats(): Map<String, List<BitchatMessage>> = chats
            override suspend fun getLoRaPeers(): List<LoRaPerson> = loRaPeopleNow ?: loRaPeople.value
            override fun observeLoRaPeers(): Flow<List<LoRaPerson>> = loRaChanges
        }

        /** The blocked mesh users; [blockListChanges] says the list changed. */
        val blocked = mutableSetOf<String>()
        val blockListChanges = MutableSharedFlow<List<BlockedUser>>(extraBufferCapacity = 1)
        private val blockList = object : BlockListRepository by mockk(relaxed = true) {
            override suspend fun isMeshUserBlocked(fingerprint: String): Boolean = fingerprint in blocked
            override fun observeBlockList(): Flow<List<BlockedUser>> = blockListChanges
        }

        fun observe() = ObserveMeshChannelPeople(repository, events, blockList)()
    }

    private fun person(id: String, name: String) = GeoPerson(id, name, seen)
    private fun lora(id: String, name: String, meshDeviceId: String?) = LoRaPerson(id, name, seen, meshDeviceId)
    private fun message(peerId: String, sender: String) = BitchatMessage("message-$peerId", sender, "", timestamp = seen, senderPeerID = peerId, isPrivate = true)
}
