package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bitchat.domain.location.geo.Geohash
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.viewvo.location.LocationChannelsState
import com.jakewharton.mosaic.layout.size
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntSize

/**
 * Location channels, in three views.
 *
 * **List** (`Up`/`Down`, `Enter`): the mesh with its participant count, the nearby channels
 * (level, geohash, place name, count, `*` when bookmarked), or instead of them the reason there are
 * none (the Pi has no location source), then the bookmarks, then rows that open the other two
 * views. `>` marks the current channel. `Enter` joins ([onSelectMesh], [onSelectChannel]; a
 * bookmark joins at the level its length implies), `b` toggles the selected geohash's bookmark,
 * `t` and `g` open the entry and the grid, `n` the selected place's notes ([onOpenNotes]).
 * `Esc` is left to the shell. A channel the user has
 * teleported into gets a row of its own, since it is neither nearby nor bookmarked and would
 * otherwise be missing from the only screen that can bookmark it.
 *
 * **Entry** (`t`): types a geohash into [geohashEditor]; `Enter` accepts 2-12 base32 characters
 * (case and a leading `#` ignored) and passes it to [onTeleport], anything else shows an error.
 *
 * **Grid** (`g`): the drill-down geohash browser that replaces the Compose globe. It starts at the
 * current or typed geohash and shows the children of a geohash north up: 8 x 4 after an even
 * length, 4 x 8 after an odd one, drawn over a half-block map of the coastline in that window
 * ([mapPicture]) with a dot for each city worth showing. A breadcrumb says where in the world one
 * is, and the line under the map names the nearest place. Where there is no room for a map, or
 * where the bundled 110m coastline has nothing left to draw, the cells fall back to the plain
 * character grid and the screen says so. Arrows move, `Enter` drills into the child holding the
 * selected cell's centre, `Backspace` (or `Delete`) goes up one level, `w` goes back to the whole
 * world in one key, `p` passes the selected geohash to [onTeleport] (two characters at least) and
 * `t` opens the entry. Every one of those is named on the grid's own key line, which is the second
 * row the budget hands out, so it cannot be squeezed off a short screen: a user who has drilled in
 * can always see how to join, how to come back up and how to leave.
 *
 * `Esc` in the entry or the grid returns to the list. All peer or geocoder text is sanitized.
 *
 * 2.7 can route [onTeleport] to `LocationChannelsViewModel.onMapResult`, which validates, picks
 * the level and teleports in one call.
 */
@Composable
fun LocationsScreen(
    state: LocationChannelsState,
    size: IntSize,
    onSelectMesh: () -> Unit,
    onSelectChannel: (GeohashChannel) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onTeleport: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenNotes: (String) -> Unit = {},
    geohashEditor: LineEditor = remember { LineEditor(maxLength = MAX_GEOHASH_LENGTH + 1) },
) {
    val consoleSafe = LocalConsoleSafe.current
    var view by remember { mutableStateOf(PlacesView.List) }
    var entryError by remember { mutableStateOf<String?>(null) }
    var cursor by remember { mutableIntStateOf(0) }
    val grid = remember { GridCursor() }
    val rows = placeRows(state)
    val selectable = rows.indices.filter { rows[it] !is PlaceRow.Note }

    fun openEntry() {
        geohashEditor.end()
        geohashEditor.deleteToStart()
        entryError = null
        view = PlacesView.Entry
    }

    fun openGrid() {
        val start = state.customGeohash.ifBlank { (state.selectedChannel as? Channel.Location)?.geohash ?: "" }
            .lowercase().filter { it in GEOHASH_BASE32 }.take(MAX_GEOHASH_LENGTH)
        grid.moveTo(start)
        view = PlacesView.Grid
    }

    fun submitEntry(line: String) {
        val geohash = parseGeohash(line)
        if (geohash != null) {
            onTeleport(geohash)
            view = PlacesView.List
        } else {
            entryError = "not a geohash"
            geohashEditor.insert(line)
        }
    }

    // The grid's own keys, and the two ways out of it that the cursor knows nothing about: `t`
    // opens the entry (it is on the list too, and a user who pressed it here got nothing), and `w`
    // goes back to the whole world, which used to take one Backspace per level.
    fun gridKey(key: String): Boolean = when (key) {
        "t" -> {
            openEntry()
            true
        }
        "w" -> {
            grid.moveTo("")
            true
        }
        else -> grid.key(key, onTeleport)
    }

    fun listKey(key: String): Boolean {
        if (selectable.isEmpty()) return false
        // Read the cursor when the key arrives: Mosaic delivers every key since the last frame first.
        val at = cursor.coerceIn(0, selectable.lastIndex)
        val row = rows[selectable[at]]
        when (key) {
            "ArrowUp" -> cursor = (at - 1).coerceAtLeast(0)
            "ArrowDown" -> cursor = (at + 1).coerceAtMost(selectable.lastIndex)
            "Enter" -> when (row) {
                PlaceRow.Mesh -> onSelectMesh()
                is PlaceRow.Nearby -> onSelectChannel(row.channel)
                is PlaceRow.Joined -> onSelectChannel(row.channel)
                is PlaceRow.Bookmark -> onSelectChannel(GeohashChannel(geohashLevel(row.geohash.length), row.geohash))
                PlaceRow.Teleport -> openEntry()
                PlaceRow.Grid -> openGrid()
                is PlaceRow.Note -> Unit
            }
            "b" -> when (row) {
                is PlaceRow.Nearby -> onToggleBookmark(row.channel.geohash)
                is PlaceRow.Joined -> onToggleBookmark(row.channel.geohash)
                is PlaceRow.Bookmark -> onToggleBookmark(row.geohash)
                else -> Unit
            }
            "n" -> when (row) {
                is PlaceRow.Nearby -> onOpenNotes(row.channel.geohash)
                is PlaceRow.Joined -> onOpenNotes(row.channel.geohash)
                is PlaceRow.Bookmark -> onOpenNotes(row.geohash)
                else -> Unit
            }
            "t" -> openEntry()
            "g" -> openGrid()
            else -> return false
        }
        return true
    }

    Column(
        modifier
            .size(size.width, size.height)
            // One handler for all three views, reading [view] when each key arrives, so keys that
            // switch views part way through a batch still reach the right one.
            .screenKeys { event ->
                val plain = !event.ctrl && !event.alt
                when {
                    plain && event.key == "Escape" && view != PlacesView.List -> {
                        view = PlacesView.List
                        true
                    }
                    view == PlacesView.Entry -> geohashEditor.handleKey(event, ::submitEntry)
                    !plain -> false
                    view == PlacesView.List -> listKey(event.key)
                    else -> gridKey(event.key)
                }
            },
    ) {
        when (view) {
            PlacesView.List -> PlacesList(state, rows, selectable, cursor, size, consoleSafe)
            PlacesView.Entry -> {
                FooterHints(EntryHints)
                // Rows by priority: the prompt, an error, the title, the hint.
                val error = entryError ?: state.customGeohashError
                val budget = RowBudget(size.height)
                val promptRows = budget.take(1)
                val errorRows = if (error != null) budget.take(1) else 0
                val titleRows = budget.take(1)
                val hintRows = budget.take(1)
                if (titleRows > 0) Text(" Join a channel by geohash code".truncateCells(size.width), color = LocalTuiTheme.current.accent, textStyle = TextStyle.Bold)
                if (promptRows > 0) {
                    LinePrompt(geohashEditor, size.width, prompt = "geohash> ", onSubmit = ::submitEntry, handlesKeys = false)
                }
                if (hintRows > 0) Text("   2-12 of 0-9 b-h j k m n p-z; Esc back".truncateCells(size.width), color = LocalTuiTheme.current.dim, textStyle = TextStyle.Dim)
                if (errorRows > 0) Text(" ${displayText(error ?: "")}".truncateCells(size.width), color = LocalTuiTheme.current.error)
            }
            PlacesView.Grid -> GridView(grid, size)
        }
    }
}

private enum class PlacesView { List, Entry, Grid }

/** The shell's key line while a geohash is being typed; the list's keys mean nothing there. */
private val EntryHints = listOf(KeyHint("Enter", "join"), KeyHint("Esc", "list"))

/** One row of the list; [Note]s are not selectable. */
private sealed interface PlaceRow {
    data object Mesh : PlaceRow
    data class Nearby(val channel: GeohashChannel) : PlaceRow

    /** The channel the user is in, when nothing else lists it (they teleported into it). */
    data class Joined(val channel: GeohashChannel) : PlaceRow
    data class Bookmark(val geohash: String) : PlaceRow
    data class Note(val text: String) : PlaceRow
    data object Teleport : PlaceRow
    data object Grid : PlaceRow
}

private fun placeRows(state: LocationChannelsState): List<PlaceRow> = buildList {
    add(PlaceRow.Mesh)
    val reason = state.locationUnavailableReason
    when {
        state.availableChannels.isNotEmpty() -> state.availableChannels.forEach { add(PlaceRow.Nearby(it)) }
        reason != null -> {
            add(PlaceRow.Note(reason))
            add(PlaceRow.Note(HINT_WITHOUT_LOCATION))
        }
        !state.locationServicesEnabled -> {
            add(PlaceRow.Note("location services are off"))
            add(PlaceRow.Note(HINT_WITHOUT_LOCATION))
        }
        else -> add(PlaceRow.Note("finding nearby channels..."))
    }
    // A teleported channel is in neither list: without a row of its own the user cannot see which
    // channel they are in, nor bookmark it, which is the whole point of this screen.
    val joined = (state.selectedChannel as? Channel.Location)
    if (joined != null &&
        state.availableChannels.none { it.geohash == joined.geohash } &&
        joined.geohash !in state.bookmarkedGeohashes
    ) {
        add(PlaceRow.Joined(GeohashChannel(joined.level, joined.geohash)))
    }
    state.bookmarkedGeohashes.forEach { add(PlaceRow.Bookmark(it)) }
    add(PlaceRow.Teleport)
    add(PlaceRow.Grid)
}

@Composable
private fun PlacesList(
    state: LocationChannelsState,
    rows: List<PlaceRow>,
    selectable: List<Int>,
    cursor: Int,
    size: IntSize,
    consoleSafe: Boolean,
) {
    val width = size.width
    val selectedRow = selectable.getOrNull(cursor.coerceIn(0, (selectable.size - 1).coerceAtLeast(0))) ?: -1
    // Rows by priority: the selected row, the title, the rest of the list.
    val budget = RowBudget(size.height)
    val listMinimum = budget.take(1)
    val titleRows = budget.take(1)
    val listRows = listMinimum + budget.take(rows.size - 1)
    val first = (selectedRow - listRows + 1).coerceAtLeast(0)
    val here = state.selectedChannel
    fun current(geohash: String) = if ((here as? Channel.Location)?.geohash == geohash) ">" else " "
    fun people(geohash: String?) = peopleCount(if (geohash == null) state.meshParticipantCount else state.participantCounts[geohash] ?: 0)
    fun safe(text: String) = displayText(text, consoleSafe)
    val theme = LocalTuiTheme.current

    if (titleRows > 0) Text(" Locations".truncateCells(width), color = theme.accent, textStyle = TextStyle.Bold)
    for (index in first until (first + listRows).coerceAtMost(rows.size)) {
        val text = when (val row = rows[index]) {
            PlaceRow.Mesh -> twoColumnRow(" ${if (here is Channel.Mesh) ">" else " "} #mesh", people(null), width)
            is PlaceRow.Nearby -> {
                val geohash = row.channel.geohash
                val name = state.locationNames[row.channel.level]?.let { " " + safe(it) } ?: ""
                val mark = if (geohash in state.bookmarkedGeohashes) " *" else ""
                val level = row.channel.level.displayName.lowercase()
                twoColumnRow(" ${current(geohash)} $level #${safe(geohash)}$name$mark", people(geohash), width)
            }
            is PlaceRow.Joined -> {
                // No place name: a teleported geohash is nowhere near whatever the geocoder found.
                val level = row.channel.level.displayName.lowercase()
                twoColumnRow(" ${current(row.channel.geohash)} $level #${safe(row.channel.geohash)}", people(row.channel.geohash), width)
            }
            is PlaceRow.Bookmark -> {
                val name = state.bookmarkNames[row.geohash]?.let { " " + safe(it) } ?: ""
                twoColumnRow(" ${current(row.geohash)} * #${safe(row.geohash)}$name", people(row.geohash), width)
            }
            is PlaceRow.Note -> "   ${safe(row.text)}".truncateCells(width)
            PlaceRow.Teleport -> "   Join a channel by geohash code...".truncateCells(width)
            PlaceRow.Grid -> "   Browse the world by region...".truncateCells(width)
        }
        // Channels in the colour they are badged with everywhere else: the mesh blue, a geohash
        // green. The rows that only open another view stay in ordinary text.
        val color = when (rows[index]) {
            PlaceRow.Mesh -> theme.mesh
            is PlaceRow.Nearby, is PlaceRow.Joined, is PlaceRow.Bookmark -> theme.geohash
            else -> theme.foreground
        }
        when {
            index == selectedRow -> Bar(text, width)
            rows[index] is PlaceRow.Note -> Text(text, color = theme.dim, textStyle = TextStyle.Dim)
            else -> Text(text, color = color)
        }
    }
}

private fun peopleCount(count: Int) = if (count == 1) "1 person " else "$count people "

/** The grid's parent geohash and selected cell. Snapshot state, read by keys when they arrive. */
private class GridCursor {
    var parent by mutableStateOf("")
    var column by mutableIntStateOf(0)
    var row by mutableIntStateOf(0)

    /** Shows [geohash]'s siblings with [geohash] selected, or the world with the top-left cell. */
    fun moveTo(geohash: String) {
        if (geohash.isEmpty()) {
            parent = ""
            column = 0
            row = 0
        } else {
            parent = geohash.dropLast(1)
            val (c, r) = gridPosition(parent, geohash.last())
            column = c
            row = r
        }
    }

    fun key(key: String, onPick: (String) -> Unit): Boolean {
        val grid = geohashGrid(parent)
        val selected = grid[row][column]
        // Arrows go to the geographic neighbour, rebuilding the grid when it lies in another parent.
        fun move(east: Int, north: Int) = geohashNeighbour(selected, east, north)?.let(::moveTo)
        when (key) {
            "ArrowLeft" -> move(-1, 0)
            "ArrowRight" -> move(1, 0)
            "ArrowUp" -> move(0, 1)
            "ArrowDown" -> move(0, -1)
            // Zooming keeps the place rather than the corner: the child holding the centre of the
            // cell just left is where the eye already was.
            "Enter" -> if (selected.length < MAX_GEOHASH_LENGTH) {
                val (lat, lon) = Geohash.decodeToCenter(selected)
                moveTo(Geohash.encode(lat, lon, selected.length + 1))
            }
            // Terminals disagree about which of the two a Backspace key sends, so take both.
            "Backspace", "Delete" -> if (parent.isNotEmpty()) moveTo(parent)
            "p" -> if (selected.length >= 2) onPick(selected)
            else -> return false
        }
        return true
    }
}

/**
 * The browser: a breadcrumb, the 32 cells of the current level, what the selected cell is, where
 * it is, and the keys.
 *
 * Rows go by priority, so a short screen keeps what matters and the map is the first thing to
 * shrink and the first to go: the selected cell's line (what `p` would join is never out of
 * sight), then one row of cells, the breadcrumb, the key hint, the place line, and everything left
 * over goes to the map.
 *
 * With room, the cells are drawn over [mapPicture]: each cell's character sits at its north-west
 * corner, and the selected cell is a reversed rectangle over the coastline inside it. Without
 * room, or when that window holds neither coast nor city, the cells fall back to the plain
 * character grid, two lines each (character and latitude over longitude) when every label fits its
 * cell and all rows fit, else one. When not all rows fit, the visible rows follow the selection.
 */
@Composable
private fun GridView(cursor: GridCursor, size: IntSize) {
    val theme = LocalTuiTheme.current
    val consoleSafe = LocalConsoleSafe.current
    val parent = cursor.parent
    val grid = geohashGrid(parent)
    val gridColumns = grid[0].size
    val gridRows = grid.size
    val precision = parent.length + 1
    val selected = grid[cursor.row][cursor.column]
    val viewport = mapViewport(parent)

    val deepest = selected.length >= MAX_GEOHASH_LENGTH
    // The shell's own line says the list's keys; while the browser is up it must say the browser's,
    // or the two lines on screen disagree about what `Enter` and `Esc` do.
    FooterHints(
        remember(deepest) {
            buildList {
                add(KeyHint("p", "join"))
                add(KeyHint("Bksp", "out"))
                add(KeyHint("w", "world"))
                if (!deepest) add(KeyHint("Enter", "in"))
                add(KeyHint("arrows", "move"))
                add(KeyHint("t", "code"))
                add(KeyHint("Esc", "list"))
            }
        },
    )

    val budget = RowBudget(size.height)
    val summaryRows = budget.take(1)
    // The keys come before the cells themselves: a user who cannot see how to join or how to come
    // back up is stuck, and a grid with no key line is what stranded one.
    val hintRows = budget.take(1)
    val minimumBodyRows = budget.take(1)
    val titleRows = budget.take(1)
    val placeRows = budget.take(1)
    val bodyRows = minimumBodyRows + budget.take(size.height)

    val picture = if (mapFits(size.width, bodyRows, gridColumns, gridRows)) {
        mapPicture(viewport, size.width, bodyRows, consoleSafe)
    } else {
        null
    }
    // An empty window is not worth a box: the cells fall back to characters, and the place line says why.
    val drawn = picture?.takeIf { it.coastline || it.places.isNotEmpty() }

    if (titleRows > 0) {
        Text(" " + breadcrumb(parent, size.width - 1), color = theme.accent, textStyle = TextStyle.Bold)
    }
    if (drawn != null) {
        for (row in 0 until bodyRows) {
            Text(mapLine(drawn, row, grid, cursor, bodyRows, theme), color = theme.foreground)
        }
    } else {
        LetterGrid(grid, cursor, size.width, bodyRows, precision, theme)
    }
    if (summaryRows > 0) {
        Text(summaryLine(selected, size.width), color = theme.foreground)
    }
    if (placeRows > 0) {
        val (lat, lon) = Geohash.decodeToCenter(selected)
        val anchor = nearestPlace(lat, lon, anchorRadius(viewport, gridColumns, gridRows))
        val notes = buildList {
            // Enter does nothing here, and a browser that simply stops responding reads as stuck.
            if (deepest) add(DEEPEST_ZOOM)
            if (picture != null && !picture.coastline) add(NO_COASTLINE)
            if (anchor != null) add("near " + displayText(anchor.name, consoleSafe))
        }
        if (notes.isNotEmpty()) {
            Text(" ${notes.joinToString("  ")}".truncateCells(size.width), color = theme.dim, textStyle = TextStyle.Dim)
        }
    }
    if (hintRows > 0) {
        Text(gridKeysLine(size.width, deepest), color = theme.dim, textStyle = TextStyle.Dim)
    }
}

/**
 * What the selected cell is: its geohash, the level its length means and that length, the centre,
 * and how big it is on the ground. Too wide for [width], the parts go from the right, because
 * where a cell is matters more than how big it is and the size is the one part that never says
 * where. What `p` would join is the first thing on the line and the last to go.
 */
internal fun summaryLine(geohash: String, width: Int): String {
    val (lat, lon) = Geohash.decodeToCenter(geohash)
    val decimals = coordinateDecimals(geohash.length)
    val parts = listOf(
        "#$geohash",
        "${geohashLevel(geohash.length).displayName.lowercase()} ${geohash.length}",
        "${formatCoordinate(lat, decimals)}, ${formatCoordinate(lon, decimals)}",
        cellSpan(geohash),
    )
    for (keep in parts.size downTo 1) {
        val line = " " + parts.take(keep).joinToString("  ")
        if (line.cellWidth() <= width) return line
    }
    return (" " + parts[0]).truncateCells(width)
}

/** What the screen says when the bundled coastline has nothing to draw in this window. */
internal const val NO_COASTLINE = "no coastline at this zoom"

/** What the screen says where `Enter` can go no further, so the browser does not read as stuck. */
internal const val DEEPEST_ZOOM = "deepest zoom"

/**
 * The grid's keys at [width] cells, most useful first so a narrow screen keeps the ones that get a
 * user out of a deep cell: join, up one level, back to the world, and the entry. `Enter` is left
 * out where it would do nothing ([deepest]). Parts go from the right until the line fits, and the
 * first alone is cut to [width] if even that is too long.
 */
internal fun gridKeysLine(width: Int, deepest: Boolean): String {
    val parts = buildList {
        add("p join")
        add("Bksp out")
        add("w world")
        add("t code")
        if (!deepest) add("Enter in")
        add("arrows move")
        add("Esc list")
    }
    for (keep in parts.size downTo 1) {
        val line = " " + parts.take(keep).joinToString("  ")
        if (line.cellWidth() <= width) return line
    }
    return (" " + parts[0]).truncateCells(width)
}

/**
 * Where in the world the browser is: `World > 9 > 9q`. Too long for [width], it keeps the tail,
 * which is the part that says where one actually is.
 */
internal fun breadcrumb(parent: String, width: Int): String {
    val full = (listOf("World") + (1..parent.length).map { parent.take(it) }).joinToString(" > ")
    if (full.cellWidth() <= width) return full
    // Below six cells the ellipsis would cost more than it says, so the tail goes on its own.
    return if (width < 6) full.takeLastCells(width) else "..." + full.takeLastCells(width - 3)
}

/** A cell of a map row that a geohash character was written into, rather than land or a city. */
private const val MAP_LETTER: Byte = 4

/**
 * Row [row] of [picture] with the cell characters of [grid] written at their north-west corners
 * and the selected cell reversed. Runs of one style are appended together, so a map row costs a
 * handful of spans rather than one per column.
 */
private fun mapLine(
    picture: MapPicture,
    row: Int,
    grid: List<List<String>>,
    cursor: GridCursor,
    rows: Int,
    theme: TuiTheme,
): AnnotatedString {
    val columns = picture.columns
    val chars = picture.glyphs[row].toCharArray()
    val layers = picture.layers[row].copyOf()
    val gridRows = grid.size
    val gridColumns = grid[0].size
    for (r in grid.indices) {
        if (cellRowStart(r, gridRows, rows) != row) continue
        for (c in 0 until gridColumns) {
            val at = cellColumnStart(c, gridColumns, columns)
            chars[at] = grid[r][c].last()
            layers[at] = MAP_LETTER
        }
    }
    val inSelectedRow = row >= cellRowStart(cursor.row, gridRows, rows) && row < cellRowStart(cursor.row + 1, gridRows, rows)
    val selectedColumns = cellColumnStart(cursor.column, gridColumns, columns) until cellColumnStart(cursor.column + 1, gridColumns, columns)
    fun selectedAt(column: Int) = inSelectedRow && column in selectedColumns
    return buildAnnotatedString {
        var start = 0
        while (start < columns) {
            val selected = selectedAt(start)
            var end = start + 1
            while (end < columns && layers[end] == layers[start] && selectedAt(end) == selected) end++
            withStyle(mapStyle(layers[start], selected, theme)) { append(chars.concatToString(start, end)) }
            start = end
        }
    }
}

/** How a map cell is coloured: the selected cell reversed, else by what drew it. */
private fun mapStyle(layer: Byte, selected: Boolean, theme: TuiTheme): SpanStyle = when {
    selected -> SpanStyle(color = theme.onAccent, background = theme.accent)
    layer == MapLayer.LAND -> SpanStyle(color = theme.dim, textStyle = TextStyle.Dim)
    layer == MapLayer.CITY || layer == MapLayer.CAPITAL -> SpanStyle(color = theme.own)
    layer == MAP_LETTER -> SpanStyle(color = theme.foreground, textStyle = TextStyle.Bold)
    else -> SpanStyle(color = theme.foreground)
}

/** The cells as plain characters, for a screen with no room for a map or a window with no map in it. */
@Composable
private fun LetterGrid(
    grid: List<List<String>>,
    cursor: GridCursor,
    width: Int,
    rows: Int,
    precision: Int,
    theme: TuiTheme,
) {
    val columns = grid[0].size
    val cellWidth = width / columns
    val labelDecimals = (coordinateDecimals(precision) downTo 0).firstOrNull { decimals ->
        grid.flatten().all { cellLabel(it, decimals).all { line -> line.cellWidth() <= cellWidth } }
    }
    val twoLines = labelDecimals != null && 2 * grid.size <= rows
    val decimals = labelDecimals ?: 0
    val linesPerRow = if (twoLines) 2 else 1
    val shownRows = (rows / linesPerRow).coerceAtMost(grid.size)
    if (shownRows <= 0) return
    val first = (cursor.row - shownRows + 1).coerceIn(0, grid.size - shownRows)
    for (r in first until first + shownRows) {
        val cells = grid[r]
        if (twoLines) {
            Text(gridLine(cells, r, cursor, cellWidth, theme) { cellLabel(it, decimals)[0] }, color = theme.foreground)
            Text(gridLine(cells, r, cursor, cellWidth, theme) { cellLabel(it, decimals)[1] }, color = theme.foreground)
        } else {
            Text(gridLine(cells, r, cursor, cellWidth, theme) { " ${it.last()}" }, color = theme.foreground)
        }
    }
}

/** A cell's two label lines: " c lat" and "   lon". */
private fun cellLabel(geohash: String, decimals: Int): List<String> {
    val (lat, lon) = Geohash.decodeToCenter(geohash)
    return listOf(" ${geohash.last()} ${formatCoordinate(lat, decimals)}", "   ${formatCoordinate(lon, decimals)}")
}

/** One text line of grid row [row]: each cell padded to [cellWidth], the selected cell reversed. */
private fun gridLine(cells: List<String>, row: Int, cursor: GridCursor, cellWidth: Int, theme: TuiTheme, text: (String) -> String): AnnotatedString =
    buildAnnotatedString {
        cells.forEachIndexed { column, geohash ->
            val cell = text(geohash).fitCells(cellWidth)
            if (row == cursor.row && column == cursor.column) {
                withStyle(SpanStyle(color = theme.onAccent, background = theme.accent)) { append(cell) }
            } else {
                append(cell)
            }
        }
    }

/**
 * How big the cell [geohash] is on the ground, roughly: `~1,900 x 5,000 km` (east-west by
 * north-south, at the cell's latitude), in metres below a kilometre, two significant figures.
 */
internal fun cellSpan(geohash: String): String {
    val bounds = Geohash.decodeToBounds(geohash)
    val middle = (bounds.latMin + bounds.latMax) / 2
    val wide = (bounds.lonMax - bounds.lonMin) * KM_PER_DEGREE * kotlin.math.cos(middle * kotlin.math.PI / 180)
    val tall = (bounds.latMax - bounds.latMin) * KM_PER_DEGREE
    return if (maxOf(wide, tall) < 1.0) "~${roughly(wide * 1000)} x ${roughly(tall * 1000)} m" else "~${roughly(wide)} x ${roughly(tall)} km"
}

/** [value] to two significant figures, with thousands separated by commas: 1917 -> "1,900", 0.61 -> "0.61". */
private fun roughly(value: Double): String {
    if (value <= 0.0) return "0"
    val magnitude = kotlin.math.floor(kotlin.math.log10(value)).toInt()
    val step = 10.0.pow(magnitude - 1)
    val rounded = kotlin.math.round(value / step) * step
    if (magnitude >= 1) {
        return rounded.toLong().toString().reversed().chunked(3).joinToString(",").reversed()
    }
    val decimals = 1 - magnitude
    val text = rounded.toString()
    val dot = text.indexOf('.')
    return if (dot < 0) text else text.take((dot + 1 + decimals).coerceAtMost(text.length)).trimEnd('0').trimEnd('.')
}

private fun Double.pow(exponent: Int): Double = kotlin.math.exp(exponent * kotlin.math.ln(this))

/** Kilometres per degree of latitude, and of longitude at the equator (a spherical Earth: this is a rough size). */
private const val KM_PER_DEGREE = 111.2

private const val HINT_WITHOUT_LOCATION = "t: join by code, g: browse by region"
