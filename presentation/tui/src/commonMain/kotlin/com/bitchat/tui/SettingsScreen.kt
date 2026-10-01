package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitchat.domain.lora.model.LoRaProtocolType
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import com.bitchat.domain.tor.model.TorAvailability
import com.bitchat.domain.tor.model.TorMode
import com.bitchat.viewvo.settings.LoRaSwitchStatus
import com.bitchat.viewvo.settings.SettingsState
import com.bitchat.viewvo.settings.ThemePreference
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntSize

/**
 * Settings as a list of rows, each a label and its value from [state]: LoRa radio on/off,
 * protocol, region, TX power, show LoRa peers, Tor on/off, proof of work on/off and its
 * difficulty (0-32 bits). Status lines under the rows explain a failed radio switch or Tor.
 * Background mode is left out: it means nothing on a terminal. The theme row picks the colours
 * the whole app draws in, the same setting the Compose apps have.
 *
 * The rows after the theme are not settings. The first is where the emergency wipe is written down;
 * the rest say which build this is ([aboutRows]): the version and, where the build has one, its
 * identity (commit, branch, clean or dirty, build time), so a board can be told from its own screen.
 *
 * Keys: `Up`/`Down` select a row (the selected one is reversed and shows `< value >`),
 * `Left`/`Right` step its value and emit the matching callback; nothing changes here until the
 * new [state] arrives. Rows that cannot change are dim and ignore the keys: the LoRa rows without a
 * radio, Tor while unavailable (switching it off is always allowed), the difficulty while proof of
 * work is off. Each callback matches a `SettingsViewModel` method (`onLoRaEnabledToggled`, ...).
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    size: IntSize,
    onLoRaEnabled: (Boolean) -> Unit,
    onLoRaProtocol: (LoRaProtocolType) -> Unit,
    onLoRaRegion: (LoRaRegion) -> Unit,
    onLoRaTxPower: (LoRaTxPower) -> Unit,
    onLoRaShowPeers: (Boolean) -> Unit,
    onTor: (Boolean) -> Unit,
    onProofOfWork: (Boolean) -> Unit,
    onPowDifficulty: (Int) -> Unit,
    onTheme: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lora = state.loraAvailable
    // The user's stored choice, not what Tor managed to do. Showing the effective value here is
    // what stranded a user with a stored ON on a build that cannot proxy: the row read "not
    // supported", was disabled, and the only thing that would have unblocked Nostr -- switching
    // Tor off -- could not be reached from inside the app.
    val torSupported = state.torAvailability != TorAvailability.NO_PROXY_SUPPORT
    val torOn = state.torNetworkEnabled || state.torBlocksNostr
    val pending = remember { PendingSettings() }

    /**
     * A row whose shown and stepped value is the one last asked for until [stateValue] moves: keys
     * in one batch add up (two `Right`s step twice) before the view model has answered.
     */
    fun <T : Any> row(label: String, stateValue: T, enabled: Boolean, show: (T) -> String, next: (T, Int) -> T, emit: (T) -> Unit) =
        SettingRow(label, show(pending.current(label, stateValue)), enabled) { step ->
            val current = pending.current(label, stateValue)
            val value = next(current, step)
            if (value != current) {
                pending.set(label, stateValue, value)
                emit(value)
            }
        }

    val powOn = pending.current(PROOF_OF_WORK, state.proofOfWorkEnabled)
    val rows = listOf(
        row("LoRa radio", state.loraEnabled, lora, { if (lora) onOff(it) else "no radio" }, { on, _ -> !on }, onLoRaEnabled),
        row("Protocol", state.loraProtocol, lora, { it.displayName }, { value, by -> step(value, by) }, onLoRaProtocol),
        row("Region", state.loraRegion, lora, ::regionLabel, { value, by -> step(value, by) }, onLoRaRegion),
        row("TX power", state.loraTxPower, lora, ::txPowerLabel, { value, by -> step(value, by) }, onLoRaTxPower),
        row("Show LoRa peers", state.loraShowPeers, lora, ::onOff, { on, _ -> !on }, onLoRaShowPeers),
        row(
            // Steppable whenever it is on, whatever the build can do, so off is always reachable;
            // once off on a build that cannot run Tor it goes dim, and nothing turns it back on.
            "Tor", torOn, torOn || (torSupported && state.torAvailable),
            { on -> if (torSupported) onOff(on) else if (on) TOR_ON_UNUSABLE else TOR_UNSUPPORTED },
            { on, _ -> !on },
            onTor,
        ),
        row(PROOF_OF_WORK, state.proofOfWorkEnabled, true, ::onOff, { on, _ -> !on }, onProofOfWork),
        row("PoW difficulty", state.powDifficulty, powOn, { "$it bits" }, { bits, by -> (bits + by).coerceIn(0, MAX_POW_DIFFICULTY) }, onPowDifficulty),
        row("Theme", state.selectedTheme, true, ::themeLabel, { value, by -> step(value, by) }, onTheme),
        // Not a setting, and not something a stray Left or Right may do: the only place the
        // emergency wipe is written down, at the end of the list where a reader will meet it.
        SettingRow("Erase everything", WIPE_KEYS, enabled = false) {},
    ) + aboutRows(displayText(state.appVersion), state.buildIdentity?.let { displayText(it) }, size.width)
    val status = statusLines(state, state.requestedTorMode == TorMode.ON)
    val theme = LocalTuiTheme.current
    var selected by remember { mutableIntStateOf(0) }
    // Rows by priority: the selected setting, the status lines (errors), the title, more settings.
    val budget = RowBudget(size.height)
    val listMinimum = budget.take(1)
    val statusRows = budget.take(status.size)
    val titleRows = budget.take(1)
    val visibleRows = listMinimum + budget.take(rows.size - 1)
    val first = (selected - visibleRows + 1).coerceAtLeast(0)

    Column(
        modifier
            .size(size.width, size.height)
            .screenKeys { event ->
                if (event.ctrl || event.alt) return@screenKeys false
                // [selected] is read when the key arrives, so several keys in one frame add up.
                when (event.key) {
                    "ArrowUp" -> selected = (selected - 1).coerceAtLeast(0)
                    "ArrowDown" -> selected = (selected + 1).coerceAtMost(rows.lastIndex)
                    "ArrowLeft" -> rows[selected].let { if (it.enabled) it.change(-1) }
                    "ArrowRight" -> rows[selected].let { if (it.enabled) it.change(1) }
                    else -> return@screenKeys false
                }
                true
            },
    ) {
        if (titleRows > 0) Text(" Settings".truncateCells(size.width), color = theme.accent, textStyle = TextStyle.Bold)
        for (index in first until (first + visibleRows).coerceAtMost(rows.size)) {
            val row = rows[index]
            val label = " " + row.label.padEndCells(LABEL_CELLS)
            when {
                index == selected -> Bar("$label< ${row.value} >", size.width)
                row.enabled -> Text("$label  ${row.value}".truncateCells(size.width), color = theme.foreground)
                else -> Text("$label  ${row.value}".truncateCells(size.width), color = theme.dim, textStyle = TextStyle.Dim)
            }
        }
        for (line in status.take(statusRows)) Text(" ${displayText(line)}".truncateCells(size.width), color = theme.dim, textStyle = TextStyle.Dim)
    }
}

/**
 * Values asked for but not yet reflected in the state, by row. Each remembers the state value it
 * was based on, and is retired as soon as the state for that row moves off that base (acknowledged,
 * or overruled by the view model) or it has come back to its base, so a later state that happens
 * to equal an old base is taken as it is.
 */
private class PendingSettings {
    private val values = HashMap<String, Pair<Any, Any>>()

    /** Bumped on every request, so what [current] shows is redrawn. */
    private var version by mutableIntStateOf(0)

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> current(row: String, stateValue: T): T {
        version // Read to redraw when a request is made.
        val (base, value) = values[row] ?: return stateValue
        if (stateValue != base || value == base) {
            values.remove(row)
            return stateValue
        }
        return value as T
    }

    fun <T : Any> set(row: String, stateValue: T, value: T) {
        values[row] = stateValue to value
        version++
    }
}

/** One settings row; [change] gets -1 for `Left`, +1 for `Right`. */
private class SettingRow(val label: String, val value: String, val enabled: Boolean, val change: (Int) -> Unit)

/**
 * What the build says about itself, as rows that cannot change: the [version], then, when the build
 * has one, its [identity] under "Build", wrapped to what is left of a [width]-cell row after the
 * label so none of it is cut (the end of it says whether the tree was dirty and when it was built).
 * Both must already be sanitized.
 */
private fun aboutRows(version: String, identity: String?, width: Int): List<SettingRow> = buildList {
    add(SettingRow("Version", version, enabled = false) {})
    if (identity == null) return@buildList
    val lines = wrapCells(identity, (width - ABOUT_VALUE_OFFSET).coerceAtLeast(MIN_ABOUT_CELLS))
    lines.forEachIndexed { index, line -> add(SettingRow(if (index == 0) "Build" else "", line, enabled = false) {}) }
}

/** Lines explaining a failed or running radio switch and Tor's state; the texts may come from the radio or Tor. */
private fun statusLines(state: SettingsState, torRequested: Boolean): List<String> = buildList {
    when (state.loraSwitchStatus) {
        LoRaSwitchStatus.SWITCHING -> add("LoRa: switching...")
        LoRaSwitchStatus.FAILED -> add("LoRa: failed" + (state.loraSwitchError?.let { ", $it" } ?: ""))
        LoRaSwitchStatus.IDLE, LoRaSwitchStatus.READY -> Unit
    }
    val torError = state.torErrorMessage
    when {
        state.torRunning -> add("Tor: running, ${state.torBootstrapPercent}%")
        torError != null -> add("Tor: $torError")
        !state.torAvailable -> add("Tor: " + torUnavailableReason(state.torAvailability, torRequested))
    }
}

private fun torUnavailableReason(availability: TorAvailability, torRequested: Boolean) = when {
    availability == TorAvailability.AVAILABLE -> "available"
    availability == TorAvailability.NATIVE_LIBRARY_MISSING -> "native library missing"
    // State and remedy in one sentence: the row says "on, unusable" and this says what that costs
    // and what turning it off buys, because the two read as a contradiction otherwise.
    torRequested -> "this build cannot run it, so nothing connects. Turn it off to use geohash channels and Nostr DMs without Tor"
    else -> "this build cannot run it; geohash channels and Nostr DMs connect without it"
}

/** `US 915 MHz`, from the region's name. */
internal fun regionLabel(region: LoRaRegion): String = region.name.replace('_', ' ') + " MHz"

/** `Low 10 dBm`, from the power's name and level. */
internal fun txPowerLabel(power: LoRaTxPower): String =
    power.name.lowercase().replaceFirstChar { it.uppercaseChar() } + " ${power.dBm} dBm"

private fun onOff(on: Boolean) = if (on) "on" else "off"

/** The enum constant [by] places after [value], wrapping around at either end. */
private inline fun <reified T : Enum<T>> step(value: T, by: Int): T {
    val all = enumValues<T>()
    return all[(value.ordinal + by).mod(all.size)]
}

/** How the emergency wipe is reached, shown in Settings; the keys themselves are in [WipeShortcut]. */
internal const val WIPE_KEYS = "Ctrl+D three times"

/** The Tor row's value where Tor could never carry anything and is off; the status line says why. */
private const val TOR_UNSUPPORTED = "not supported"

/**
 * The Tor row's value where the stored choice is on and this build cannot carry it. It has to read
 * as on, or the screen contradicts the notice every chat is showing, and it has to read as broken,
 * or there is no reason to turn it off. The status line carries the rest.
 */
private const val TOR_ON_UNUSABLE = "on, unusable"

private const val LABEL_CELLS = 17
private const val PROOF_OF_WORK = "Proof of work"
private const val MAX_POW_DIFFICULTY = 32

/**
 * What a selected row spends around its value: the space and the label, then `< ` before it and
 * ` >` after. The build's identity is wrapped short of this, so the selected line is shown whole.
 */
private const val ABOUT_VALUE_OFFSET = 1 + LABEL_CELLS + 2 + 2

/** The narrowest an identity line is wrapped to, whatever the width; a row still cuts at the screen's edge. */
private const val MIN_ABOUT_CELLS = 8

/** How a theme choice is named in the settings row. */
internal fun themeLabel(theme: ThemePreference): String = when (theme) {
    ThemePreference.SYSTEM -> "terminal"
    ThemePreference.DARK -> "dark"
    ThemePreference.LIGHT -> "light"
}
