package com.bitchat.domain.chat.command

import com.bitchat.domain.chat.ProcessChatCommand
import com.bitchat.domain.chat.model.ChatCommand
import com.bitchat.domain.chat.model.CommandContext
import com.bitchat.domain.chat.model.CommandResult
import com.bitchat.domain.chat.model.failure.CommandFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessChatCommandTest {
    private val useCase = ProcessChatCommand()

    @Test
    fun `parses hug command with target`() = runTest {
        val result = useCase(
            ProcessChatCommand.ChatCommandRequest(
                input = "/hug @anon6230#3098",
                context = CommandContext(isLocationChannel = true, isMeshChannel = true, isNamedChannel = false, currentChannel = null)
            )
        )

        val parsed = assertIs<CommandResult.Parsed>(result)
        val command = assertIs<ChatCommand.Hug>(parsed.command)
        assertEquals("anon6230#3098", command.target)
    }

    @Test
    fun `returns invalid when hug has no target`() = runTest {
        val result = useCase(
            ProcessChatCommand.ChatCommandRequest(
                input = "/hug",
                context = CommandContext(isLocationChannel = true, isMeshChannel = true, isNamedChannel = false, currentChannel = null)
            )
        )

        val invalid = assertIs<CommandResult.Invalid>(result)
        assertEquals(CommandFailure.MissingTarget, invalid.failure)
    }

    @Test
    fun `rejects join command outside mesh`() = runTest {
        val result = useCase(
            ProcessChatCommand.ChatCommandRequest(
                input = "/j #general",
                context = CommandContext(isLocationChannel = true, isMeshChannel = false, isNamedChannel = false, currentChannel = null)
            )
        )

        val parsed = assertIs<CommandResult.Parsed>(result)
        val command = assertIs<ChatCommand.Join>(parsed.command)
        assertEquals("#general", command.channel)
    }

    @Test
    fun `rejects favorite command outside location`() = runTest {
        val result = useCase(
            ProcessChatCommand.ChatCommandRequest(
                input = "/favorite @anon",
                context = CommandContext(isLocationChannel = false, isMeshChannel = true, isNamedChannel = false, currentChannel = null)
            )
        )

        assertTrue(result is CommandResult.NotACommand)
    }

    @Test
    fun `parses who command`() = runTest {
        val result = useCase(
            ProcessChatCommand.ChatCommandRequest(
                input = "/w",
                context = CommandContext(isLocationChannel = false, isMeshChannel = true, isNamedChannel = false, currentChannel = null)
            )
        )

        val parsed = assertIs<CommandResult.Parsed>(result)
        val command = assertIs<ChatCommand.Who>(parsed.command)
        assertEquals(ChatCommand.Who(null), command)
    }
}
