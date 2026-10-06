package com.bitchat.design.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The header counted a peer heard only over LoRa while the drawer it opens said "No one connected":
 * the count read the mesh and LoRa lists, the drawer only the mesh one.
 */
class PeopleSectionTest {
    private fun loraPerson() = GeoPerson("lora-radio", "radio", Instant.fromEpochSeconds(0))

    @Test
    fun `a LoRa peer keeps the people section from being empty`() {
        assertFalse(peopleSectionIsEmpty(emptyList(), listOf(loraPerson())))
    }

    @Test
    fun `the people section is empty with no mesh or LoRa peers`() {
        assertTrue(peopleSectionIsEmpty(emptyList(), emptyList()))
    }

    @Test
    fun `drawer emptiness agrees with the mesh header count`() {
        val lora = listOf(loraPerson())
        listOf(
            emptyList<String>() to emptyList<GeoPerson>(),
            listOf("mesh") to emptyList(),
            emptyList<String>() to lora,
            listOf("mesh") to lora,
        ).forEach { (connectedPeers, loraPeers) ->
            val count = peerCountFor(Channel.Mesh, connectedPeers, emptyList(), loraPeers).count
            assertEquals(count > 0, !peopleSectionIsEmpty(connectedPeers, loraPeers))
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer lists a peer heard only over LoRa`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                PeopleSection(
                    connectedPeers = emptyList(),
                    loraPeers = listOf(loraPerson()),
                    peerNicknames = emptyMap(),
                    peerDirect = emptyMap(),
                    nickname = "me",
                    selectedPrivatePeer = null,
                    favoritePeers = emptySet(),
                    hasUnreadPrivateMessages = emptySet(),
                    privateChats = emptyMap(),
                    onMeshPersonTap = {},
                    onToggleFavorite = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("radio").assertExists()
        onNodeWithText("LoRa").assertExists()
        onNodeWithText("No one connected").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer shows a fixed chats current announcement`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                PeopleSection(
                    connectedPeers = listOf("id-alice"),
                    peerNicknames = mapOf("id-alice" to "alice#1a2b"),
                    claimedNames = mapOf("id-alice" to "bob"),
                    fixedNamePeers = setOf("id-alice"),
                    peerDirect = mapOf("id-alice" to true),
                    nickname = "me",
                    selectedPrivatePeer = null,
                    favoritePeers = emptySet(),
                    hasUnreadPrivateMessages = emptySet(),
                    privateChats = emptyMap(),
                    onMeshPersonTap = {},
                    onToggleFavorite = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("alice").assertExists()
        onNodeWithText("now: bob").assertExists()
        // Which device this is matters exactly then: the start of its id is shown with the name.
        onNodeWithText("#1a2b").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer shows a private chat's own name in full whether or not its peer announces another`() = runComposeUiTest {
        // The start of the id is there from the first: an announcement that comes later adds its own line
        // and takes no room from the name.
        setContent {
            MaterialTheme {
                PeopleSection(
                    connectedPeers = listOf("id-alice"),
                    peerNicknames = mapOf("id-alice" to "alice#1a2b"),
                    fixedNamePeers = setOf("id-alice"),
                    peerDirect = mapOf("id-alice" to true),
                    nickname = "me",
                    selectedPrivatePeer = null,
                    favoritePeers = emptySet(),
                    hasUnreadPrivateMessages = emptySet(),
                    privateChats = emptyMap(),
                    onMeshPersonTap = {},
                    onToggleFavorite = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("alice").assertExists()
        onNodeWithText("#1a2b").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the drawer leaves out the suffix of a lone name that is no private chat's own`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                PeopleSection(
                    connectedPeers = listOf("dora#9f3c"),
                    peerNicknames = emptyMap(),
                    peerDirect = emptyMap(),
                    nickname = "me",
                    selectedPrivatePeer = null,
                    favoritePeers = emptySet(),
                    hasUnreadPrivateMessages = emptySet(),
                    privateChats = emptyMap(),
                    onMeshPersonTap = {},
                    onToggleFavorite = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("#9f3c").assertDoesNotExist()
    }
}
