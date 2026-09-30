package com.bitchat.viewvo.chat

import com.bitchat.domain.location.model.Channel

private val baseCommandSuggestions = listOf(
    CommandSuggestion("/block", emptyList(), "[nickname]", "block or list blocked peers"),
    CommandSuggestion("/channels", emptyList(), null, "show all discovered channels"),
    CommandSuggestion("/clear", emptyList(), null, "clear chat messages"),
    CommandSuggestion("/hug", emptyList(), "<nickname>", "send someone a warm hug"),
    CommandSuggestion("/j", listOf("/join"), "<channel>", "join or create a channel"),
    CommandSuggestion("/m", listOf("/msg"), "<nickname> [message]", "send private message"),
    CommandSuggestion("/slap", emptyList(), "<nickname> [object]", "slap someone with a chosen object"),
    CommandSuggestion("/unblock", emptyList(), "<nickname>", "unblock a peer"),
    CommandSuggestion("/w", listOf("/who"), null, "see who's online"),
)

private val meshOnlySuggestions = listOf<CommandSuggestion>(

)

private val channelOnlySuggestions = listOf(
    CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
    CommandSuggestion("/leave", emptyList(), "", "leave the channel"),
)

private val locationOnlySuggestions = listOf<CommandSuggestion>(

)

/**
 * The slash commands offered in this chat, the one list both the Compose chat and the terminal UI
 * suggest from.
 */
fun Channel.channelCommandSuggestions(currentChannel: String?): List<CommandSuggestion> {
    val meshSuggestions = if (this is Channel.Mesh) meshOnlySuggestions else emptyList()
    val channelSuggestions = if (this is Channel.NamedChannel) channelOnlySuggestions else emptyList()
    val locationSuggestions = if (this is Channel.Location) locationOnlySuggestions else emptyList()
    return baseCommandSuggestions + meshSuggestions + channelSuggestions + locationSuggestions
}

/**
 * The commands to suggest while [input] is being typed: none unless it starts with `/`; otherwise
 * those whose command or an alias starts with it (ignoring case), by command. A typed space ends
 * the suggestions, since no command contains one.
 */
fun List<CommandSuggestion>.matching(input: String): List<CommandSuggestion> {
    if (!input.startsWith("/")) return emptyList()
    val lowered = input.lowercase()
    return filter { command -> command.command.startsWith(lowered) || command.aliases.any { it.startsWith(lowered) } }
        .sortedBy { it.command }
}

/**
 * Whether this command's first argument is somebody's nickname, read from the [syntax] the list
 * above already declares. That syntax is the single statement of a command's shape, and it agrees
 * with `ProcessChatCommand`: `/hug`, `/slap`, `/m`, `/msg`, `/block` and `/unblock` take a nickname
 * there, and `/j`, `/join`, `/w`, `/who`, `/leave` and `/pass` take something else. Deriving it
 * here rather than listing the commands again keeps one copy of that fact.
 */
val CommandSuggestion.takesNicknameFirst: Boolean
    get() = syntax?.trimStart()?.let { it.startsWith("<nickname>") || it.startsWith("[nickname]") } == true

/**
 * One row of the input's suggestion list, whatever it is a suggestion of: the whole line `Tab`
 * completes to (trailing space included, so the next word can just be typed), the row's own text
 * and the dim explanation beside it.
 */
data class InputSuggestion(
    val line: String,
    val label: String,
    val detail: String = "",
)

/**
 * What to offer under a chat input holding [input]: the people in the conversation once a command
 * that takes a nickname has been typed, and otherwise the commands themselves.
 *
 * [people] are the display names of whoever is in this conversation, as the peers list shows them,
 * so a name may carry the `#abcd` suffix two people of the same nickname are told apart by. A row
 * completes to the whole name; `ChatViewModel` matches that exactly, where a bare `bob` only
 * matches by its `bob#` prefix and is ambiguous by construction.
 */
fun inputSuggestions(
    commands: List<CommandSuggestion>,
    people: List<String>,
    input: String,
): List<InputSuggestion> {
    val nickname = nicknameArgument(input, commands)
    if (nickname != null) {
        val (command, prefix) = nickname
        return people.matchingNicknames(prefix).map { InputSuggestion(line = "$command $it ", label = it) }
    }
    return commands.matching(input).map { command ->
        val syntax = command.syntax?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        InputSuggestion(line = command.command + " ", label = command.command + syntax, detail = command.description)
    }
}

/** The names starting with [prefix] (ignoring case), each once, in name order. */
fun List<String>.matchingNicknames(prefix: String): List<String> =
    filter { it.startsWith(prefix, ignoreCase = true) }.distinct().sortedBy { it.lowercase() }

/**
 * The command as typed and the nickname begun after it, while [input] is a command that takes a
 * nickname first and nothing past that nickname has been typed. Null otherwise: while the command
 * itself is still being typed, for a command whose argument is not a person, and as soon as the
 * space after the nickname starts the message, which is where `/m bob hello` ends up.
 */
internal fun nicknameArgument(input: String, commands: List<CommandSuggestion>): Pair<String, String>? {
    if (!input.startsWith("/")) return null
    val space = input.indexOf(' ')
    if (space < 0) return null
    val typed = input.substring(0, space)
    val argument = input.substring(space + 1)
    if (argument.contains(' ')) return null
    val takesNickname = commands.any { command ->
        command.takesNicknameFirst &&
            (command.command.equals(typed, ignoreCase = true) || command.aliases.any { it.equals(typed, ignoreCase = true) })
    }
    // `@bob` is `bob` to ProcessChatCommand; the list should follow the line rather than refuse it.
    return if (takesNickname) typed to argument.removePrefix("@") else null
}
