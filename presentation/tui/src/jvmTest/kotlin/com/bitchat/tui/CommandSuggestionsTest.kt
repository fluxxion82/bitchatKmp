package com.bitchat.tui

import com.bitchat.domain.location.model.Channel
import com.bitchat.viewvo.chat.channelCommandSuggestions
import com.bitchat.viewvo.chat.inputSuggestions
import com.bitchat.viewvo.chat.matching
import com.bitchat.viewvo.chat.takesNicknameFirst
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandSuggestionsTest {
    private val mesh = Channel.Mesh.channelCommandSuggestions(null)

    @Test fun commandsAndAliasesMatchByPrefixIgnoringCase() {
        assertEquals(listOf("/block", "/channels", "/clear", "/hug", "/j", "/m", "/slap", "/unblock", "/w"), mesh.matching("/").map { it.command })
        assertEquals(listOf("/j"), mesh.matching("/JO").map { it.command })
        assertEquals(listOf("/m"), mesh.matching("/ms").map { it.command })
        assertEquals(emptyList(), mesh.matching("/m bob").map { it.command }, "a space ends the suggestions")
        assertEquals(emptyList(), mesh.matching("hello").map { it.command })
    }

    @Test fun namedChannelsAlsoOfferLeaveAndPass() {
        assertEquals(listOf("/leave"), Channel.NamedChannel("#test").channelCommandSuggestions("#test").matching("/le").map { it.command })
        assertEquals(emptyList(), mesh.matching("/le").map { it.command })
    }

    private val people = listOf("bob#a1b2", "bob#c3d4", "alice", "Carol")

    private fun offered(input: String) = inputSuggestions(mesh, people, input).map { it.label }

    @Test fun onlyTheCommandsWhoseFirstArgumentIsAPersonOfferPeople() {
        // Read from each command's own syntax, which is what ProcessChatCommand implements.
        assertEquals(
            listOf("/block", "/hug", "/m", "/slap", "/unblock"),
            mesh.filter { it.takesNicknameFirst }.map { it.command },
        )
        assertEquals(listOf("alice", "bob#a1b2", "bob#c3d4", "Carol"), offered("/hug "))
        assertEquals(listOf("alice", "bob#a1b2", "bob#c3d4", "Carol"), offered("/msg "))
        // A channel is not a person, and neither is a password.
        assertEquals(emptyList(), offered("/join "))
        assertEquals(emptyList(), offered("/who "))
        assertEquals(emptyList(), offered("/clear "))
    }

    @Test fun peopleAreFilteredByWhatHasBeenTypedOfTheName() {
        assertEquals(listOf("bob#a1b2", "bob#c3d4"), offered("/hug bo"))
        assertEquals(listOf("bob#c3d4"), offered("/hug bob#c"), "the suffix is part of the name")
        assertEquals(listOf("Carol"), offered("/hug car"), "case is ignored, as everywhere else")
        assertEquals(listOf("bob#a1b2", "bob#c3d4"), offered("/hug @bo"), "an @ is not part of the name")
        assertEquals(emptyList(), offered("/hug dave"))
    }

    @Test fun theListClosesOnceTheNicknameIsDoneAndTheMessageBegins() {
        assertEquals(emptyList(), offered("/m bob#a1b2 "))
        assertEquals(emptyList(), offered("/m bob#a1b2 hello"))
    }

    @Test fun aRowCompletesToTheWholeLineWithTheNameAsTheAppsMatchIt() {
        // The full display name, suffix included: ChatViewModel matches that exactly, where a bare
        // "bob" only matches through its "bob#" prefix and is ambiguous by construction.
        assertEquals("/hug bob#c3d4 ", inputSuggestions(mesh, people, "/hug bob#c").single().line)
        assertEquals("/m alice ", inputSuggestions(mesh, people, "/m al").single().line)
        // The command keeps the spelling that was typed, alias and all.
        assertEquals("/msg alice ", inputSuggestions(mesh, people, "/msg al").single().line)
    }

    @Test fun commandRowsStillCompleteToTheCommand() {
        assertEquals(listOf("/m <nickname> [message]"), inputSuggestions(mesh, people, "/ms").map { it.label })
        assertEquals("/m ", inputSuggestions(mesh, people, "/ms").single().line)
        assertEquals("send private message", inputSuggestions(mesh, people, "/ms").single().detail)
    }
}
