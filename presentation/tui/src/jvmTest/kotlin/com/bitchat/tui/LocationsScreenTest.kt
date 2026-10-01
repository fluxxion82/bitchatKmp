package com.bitchat.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.viewvo.location.LocationChannelsState
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Rows written by hand. The first row of every snapshot is the test's own action counter. Every
 * row carries a theme colour, so Mosaic keeps the blanks that pad it out to the full width.
 */
class LocationsScreenTest {
    private val esc = "\u001B"
    private val bar = "$esc[30;102m" // A selected row: the dark theme's black on bright green.
    private val note = "$esc[32;2m" // A note, in the dimmer green.
    private val reset = "$esc[0m"
    private val actions = mutableStateListOf<String>()
    private val unhandled = mutableStateListOf<String>()

    // The Pi: no location source, one bookmark.
    private val pi = LocationChannelsState(
        meshParticipantCount = 3,
        locationUnavailableReason = "no location source",
        bookmarkedGeohashes = listOf("9q8yy"),
        participantCounts = mapOf("9q8yy" to 2),
        bookmarkNames = mapOf("9q8yy" to "SF"),
    )

    private val nearby = LocationChannelsState(
        availableChannels = listOf(GeohashChannel(GeohashChannelLevel.REGION, "9q"), GeohashChannel(GeohashChannelLevel.CITY, "9q8yy")),
        selectedChannel = Channel.Location(GeohashChannelLevel.CITY, "9q8yy"),
        locationNames = mapOf(GeohashChannelLevel.CITY to "San Francisco"),
        participantCounts = mapOf("9q8yy" to 1),
        bookmarkedGeohashes = listOf("9q"),
        locationServicesEnabled = true,
    )

    @Composable
    private fun Screen(state: LocationChannelsState, size: IntSize) {
        Column(Modifier.onKeyEvent { unhandled += it.key; true }) {
            Text("actions ${actions.size} unhandled ${unhandled.size}")
            LocationsScreen(
                state = state,
                size = size,
                onSelectMesh = { actions += "mesh" },
                onSelectChannel = { actions += "channel=${it.level} ${it.geohash}" },
                onToggleBookmark = { actions += "bookmark=$it" },
                onTeleport = { actions += "teleport=$it" },
            )
        }
    }

    private suspend fun render(level: AnsiLevel, state: LocationChannelsState, size: IntSize, consoleSafe: Boolean = false): List<String> =
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot { CompositionLocalProvider(LocalConsoleSafe provides consoleSafe) { Screen(state, size) } }
                .draw().render(level, false).split("\n").drop(1)
        }

    private fun TestMosaic<*>.type(vararg codes: Int) = codes.forEach { sendKeyEvent(KeyboardEvent(it)) }

    private fun TestMosaic<*>.type(text: String) = text.forEach { sendKeyEvent(KeyboardEvent(it.code)) }

    private fun pad(left: String, right: String, width: Int) = left + " ".repeat(width - left.length - right.length) + right

    /** The browser's line for the selected cell, whatever the map above it left behind. */
    private fun cell(rows: List<String>) = rows.first { it.startsWith(" #") }

    /** One frame the grid is checked in: its size, how many rows the map gets, and two of its lines. */
    private data class GridFrame(val size: IntSize, val mapRows: Int, val summary: String, val keys: String)

    /** One grid row: every cell padded to [cell] columns, the trailing blanks included. */
    private fun gridRow(cell: Int, vararg cells: String) = cells.joinToString("") { it.padEnd(cell) }

    @Test fun piListAt40x9() = runTest {
        assertEquals(
            listOf(
                " Locations",
                pad(" > #mesh", "3 people ", 40),
                "   no location source",
                "   t: join by code, g: browse by region",
                pad("   * #9q8yy SF", "2 people ", 40),
                "   Join a channel by geohash code...",
                "   Browse the world by region...",
                "",
                "",
            ),
            render(AnsiLevel.NONE, pi, IntSize(40, 9)),
        )
    }

    @Test fun piListAt85x22() = runTest {
        val rows = render(AnsiLevel.NONE, pi, IntSize(85, 22))
        assertEquals(pad(" > #mesh", "3 people ", 85), rows[1])
        assertEquals(pad("   * #9q8yy SF", "2 people ", 85), rows[4])
        assertEquals(22, rows.size)
    }

    @Test fun nearbyChannelsShowLevelNameCountAndMarks() = runTest {
        assertEquals(
            listOf(
                " Locations",
                pad("   #mesh", "0 people ", 60),
                pad("   region #9q *", "0 people ", 60),
                pad(" > city #9q8yy San Francisco", "1 person ", 60),
                pad("   * #9q", "0 people ", 60),
                "   Join a channel by geohash code...",
                "   Browse the world by region...",
            ),
            render(AnsiLevel.NONE, nearby, IntSize(60, 7)),
        )
    }

    @Test fun notesAreDimAndTheSelectedRowIsReversed() = runTest {
        val rows = render(AnsiLevel.ANSI16, pi, IntSize(40, 9))
        assertEquals(bar + pad(" > #mesh", "3 people ", 40) + reset, rows[1])
        assertEquals("$note   no location source$reset", rows[2])
    }

    /** The Pi after a teleport: no location source, so the channel joined is in neither list. */
    private val teleported = pi.copy(
        bookmarkedGeohashes = emptyList(),
        selectedChannel = Channel.Location(GeohashChannelLevel.CITY, "9q8yy"),
        participantCounts = mapOf("9q8yy" to 4),
    )

    @Test fun theChannelTeleportedIntoHasARowOfItsOwn() = runTest {
        val rows = render(AnsiLevel.NONE, teleported, IntSize(40, 9))
        assertEquals(pad(" > city #9q8yy", "4 people ", 40), rows[4], rows.toString())
    }

    @Test fun bBookmarksTheChannelTeleportedInto() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(teleported, IntSize(40, 9)) }
            type(KeyboardEvent.Down, 'b'.code) // Down past the mesh onto it; the notes are skipped.
            awaitSnapshot()
        }
        assertEquals(listOf("bookmark=9q8yy"), actions)
    }

    @Test fun nOpensTheNotesOfTheSelectedPlace() = runTest {
        val opened = mutableStateListOf<String>()
        runMosaicTest {
            setContentAndSnapshot {
                Column(Modifier.onKeyEvent { unhandled += it.key; true }) {
                    Text("opened ${opened.size}")
                    LocationsScreen(teleported, IntSize(40, 9), {}, {}, {}, {}, onOpenNotes = { opened += it })
                }
            }
            type(KeyboardEvent.Down, 'n'.code)
            awaitSnapshot()
        }
        assertEquals(listOf("9q8yy"), opened.toList())
    }

    @Test fun nOnTheMeshDoesNothingSinceItIsNowhere() = runTest {
        val opened = mutableStateListOf<String>()
        runMosaicTest {
            setContentAndSnapshot {
                Column(Modifier.onKeyEvent { unhandled += it.key; true }) {
                    Text("opened ${opened.size}")
                    LocationsScreen(teleported, IntSize(40, 9), {}, {}, {}, {}, onOpenNotes = { opened += it })
                }
            }
            type('n'.code, KeyboardEvent.Down) // The Down only gives the frame something to change.
            awaitSnapshot()
        }
        assertEquals(emptyList(), opened.toList())
    }

    @Test fun aChannelAlreadyListedDoesNotGetASecondRow() = runTest {
        // It is nearby, so the nearby row already marks it and bookmarks it.
        val rows = render(AnsiLevel.NONE, nearby, IntSize(60, 9))
        assertEquals(1, rows.count { it.contains("#9q8yy") }, rows.toString())
    }

    @Test fun waitingForAFixSaysSo() = runTest {
        val rows = render(AnsiLevel.NONE, LocationChannelsState(locationServicesEnabled = true), IntSize(40, 5))
        assertEquals("   finding nearby channels...", rows[2])
    }

    @Test fun locationServicesOffStillShowsTheCounts() = runTest {
        // The counts need no fix, so switching location off hides the nearby channels, not them.
        assertEquals(
            listOf(
                " Locations",
                pad(" > #mesh", "3 people ", 40),
                "   location services are off",
                "   t: join by code, g: browse by region",
                pad("   * #9q8yy SF", "2 people ", 40),
                "   Join a channel by geohash code...",
                "   Browse the world by region...",
                "",
                "",
            ),
            render(AnsiLevel.NONE, pi.copy(locationUnavailableReason = null), IntSize(40, 9)),
        )
    }

    @Test fun enterJoinsAndBBookmarks() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type(13, KeyboardEvent.Down, 13, 'b'.code)
            awaitSnapshot()
        }
        assertEquals(listOf("mesh", "channel=CITY 9q8yy", "bookmark=9q8yy"), actions)
    }

    @Test fun nearbyChannelsJoinAsThemselves() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(nearby, IntSize(60, 7)) }
            type(KeyboardEvent.Down, 13, KeyboardEvent.Down, 'b'.code)
            awaitSnapshot()
        }
        assertEquals(listOf("channel=REGION 9q", "bookmark=9q8yy"), actions)
    }

    @Test fun teleportEntryAcceptsAGeohash() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type('t'.code)
            assertEquals(
                listOf(" Join a channel by geohash code", "geohash>  ", "   2-12 of 0-9 b-h j k m n p-z; Esc back"),
                awaitSnapshot().lines().drop(1).take(3),
            )
            type("#9Q8YY")
            type(13)
            assertEquals(" Locations", awaitSnapshot().lines()[1])
        }
        assertEquals(listOf("teleport=9q8yy"), actions)
    }

    @Test fun tTheGeohashAndEnterInOneBatchTeleports() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type('t'.code) // No snapshot in between: all of these reach the screen in one batch.
            type("9q8yy")
            type(13)
            awaitSnapshot()
        }
        assertEquals(listOf("teleport=9q8yy"), actions)
    }

    @Test fun escThenEnterInOneBatchCancelsTheEntry() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type('t'.code)
            awaitSnapshot()
            type("9q8yy")
            awaitSnapshot()
            type(27, 13) // Back to the list, then Enter on its selected row (the mesh).
            awaitSnapshot()
        }
        assertEquals(listOf("mesh"), actions)
    }

    @Test fun teleportEntryRejectsWhatIsNotAGeohash() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type('t'.code)
            awaitSnapshot() // Keys go to the views of the last frame, so let the entry appear first.
            type("9a")
            type(13)
            val rows = awaitSnapshot().lines().drop(1)
            assertEquals("geohash> 9a ", rows[1])
            assertEquals(" not a geohash", rows[3])
        }
        assertEquals(emptyList(), actions)
    }

    @Test fun escLeavesTheEntryWithoutLeavingTheScreen() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type('t'.code)
            awaitSnapshot()
            type(27)
            assertEquals(" Locations", awaitSnapshot().lines()[1])
        }
        assertEquals(emptyList(), unhandled)
    }

    @Test fun escOnTheListIsLeftForTheShell() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type(27)
            awaitSnapshot()
        }
        assertEquals(listOf("Escape"), unhandled)
    }

    // The map's rows are not asserted character by character here. The rasteriser has its own
    // tests, worked out by hand from synthetic polygons (WorldMapTest); what belongs here is the
    // frame around it: where the map sits, how wide its rows are, and that it is drawn in nothing
    // but the four glyphs the Linux console font has and the 32 cell characters.
    private val mapGlyphs = setOf(' ', '\u2588', '\u2580', '\u2584')
    // The grid's key line: every key where the width allows, and on a narrow screen the four that
    // get a user out of a cell they have drilled into (join, up, world, the code entry).
    private val gridKeys = " p join  Bksp out  w world  t code  Enter in  arrows move  Esc list"
    private val gridKeysNarrow = " p join  Bksp out  w world  t code"

    private val land = "$esc[32;2m" // The dimmer green, as notes are drawn in.
    private val city = "$esc[93m" // One's own orange, which sixteen colours round to bright yellow.

    @Test fun theWorldIsAMapUnderTheThirtyTwoCellsAt40x9() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi.copy(bookmarkedGeohashes = emptyList()), IntSize(40, 9)) }
            type('g'.code)
            val rows = awaitSnapshot().lines().drop(1)
            assertEquals(9, rows.size)
            assertEquals(" World", rows[0])
            assertEquals(" #b  region 1  68, -158", rows[6]) // Forty columns leave no room for the size.
            assertEquals(" near Point Hope", rows[7])
            assertEquals(gridKeysNarrow, rows[8])
            val map = rows.subList(1, 6)
            assertEquals(List(5) { 40 }, map.map { it.length })
            assertEquals(GEOHASH_BASE32.toSet(), map.joinToString("").toSet() - mapGlyphs)
        }
    }

    @Test fun theMapTakesEveryRowTheOtherLinesLeave() = runTest {
        // Five lines are spoken for at any size: the breadcrumb, the cell line, the place line, the
        // hint, and the row the cells always keep. Everything else goes to the map.
        val sizes = listOf(
            GridFrame(IntSize(40, 12), 8, " #b  region 1  68, -158", gridKeysNarrow),
            GridFrame(IntSize(85, 25), 21, " #b  region 1  68, -158  ~1,900 x 5,000 km", gridKeys),
            GridFrame(IntSize(240, 67), 63, " #b  region 1  68, -158  ~1,900 x 5,000 km", gridKeys),
        )
        for ((size, mapRows, summary, keys) in sizes) {
            runMosaicTest {
                setContentAndSnapshot { Screen(pi.copy(bookmarkedGeohashes = emptyList()), size) }
                type('g'.code)
                val rows = awaitSnapshot().lines().drop(1)
                assertEquals(size.height, rows.size, "$size")
                assertEquals(" World", rows[0], "$size")
                val map = rows.subList(1, 1 + mapRows)
                assertEquals(List(mapRows) { size.width }, map.map { it.length }, "$size")
                assertEquals(GEOHASH_BASE32.toSet(), map.joinToString("").toSet() - mapGlyphs, "$size")
                assertEquals(summary, rows[1 + mapRows], "$size")
                assertEquals(" near Point Hope", rows[2 + mapRows], "$size")
                assertEquals(keys, rows[size.height - 1], "$size")
            }
        }
    }

    @Test fun theSelectedCellIsReversedOverTheMap() = runTest {
        runMosaicTest(MosaicSnapshots) {
            setContentAndSnapshot { Screen(pi.copy(bookmarkedGeohashes = emptyList()), IntSize(40, 9)) }
            type('g'.code)
            // Row two of the frame is the map's first row, and #b is its first five columns.
            val row = awaitSnapshot().draw().render(AnsiLevel.ANSI16, false).split("\n")[2]
            assertTrue(row.startsWith("${bar}b"), row)
            type(KeyboardEvent.Right)
            val moved = awaitSnapshot().draw().render(AnsiLevel.ANSI16, false).split("\n")[2]
            assertTrue(!moved.startsWith(bar), moved)
            // The reversed run now carries #c; the escape may also turn bold off on the way in.
            assertTrue(moved.contains(Regex(Regex.escape("$esc[30;102") + "[0-9;]*mc")), moved)
        }
    }

    @Test fun landIsDimAndCitiesTakeTheColourOfOnesOwnMessages() = runTest {
        runMosaicTest(MosaicSnapshots) {
            // #9 is the eastern Pacific and the American west, which has cities to mark.
            setContentAndSnapshot { Screen(pi.copy(customGeohash = "9q"), IntSize(85, 25)) }
            type('g'.code)
            val frame = awaitSnapshot().draw().render(AnsiLevel.ANSI16, false)
            assertTrue(frame.contains("$land\u2588"), "land is dim")
            assertTrue(frame.contains("$city*"), "capitals are in one's own colour")
        }
    }

    @Test fun theBrowserStartsAtTheSelectedChannel() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(nearby, IntSize(85, 22)) }
            type('g'.code)
            val rows = awaitSnapshot().lines().drop(1)
            assertEquals(" World > 9 > 9q > 9q8 > 9q8y", rows[0])
            assertEquals(" #9q8yy  city 5  37.77, -122.41  ~3.8 x 4.8 km", rows[19])
            assertEquals(" near San Francisco", rows[20])
        }
    }

    @Test fun enterZoomsToThePlaceUnderTheCursorAndBackspaceComesBack() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 12)) }
            type('g'.code, 'p'.code) // A single character is too coarse to pick.
            type(KeyboardEvent.Right, 13) // Into "c": an odd parent, so four columns by eight rows.
            val inside = awaitSnapshot().lines().drop(1)
            assertEquals(" World > c", inside[0])
            // The child holding the centre of #c is selected, not the north-west corner #cp.
            assertEquals(" #cs  region 2  70, -107  ~420 x 630 km", inside[9])
            type(127) // Backspace: back up with "c" still selected.
            assertEquals(" #c  region 1  68, -113", awaitSnapshot().lines().drop(1)[9])
            type(13, 'p'.code) // Into #c again, and pick the same child.
            awaitSnapshot()
        }
        assertEquals(listOf("teleport=cs"), actions)
    }

    @Test fun whereThereIsNoCoastLeftTheCellsComeBackAsCharacters() = runTest {
        runMosaicTest {
            // Inland California at 130 metres a cell: the bundled coastline rounds to two decimals,
            // so the whole window is land and a screen of solid blocks would say nothing.
            setContentAndSnapshot { Screen(pi.copy(customGeohash = "9qhh0pb"), IntSize(85, 22)) }
            type('g'.code)
            val rows = awaitSnapshot().lines().drop(1)
            assertEquals(" World > 9 > 9q > 9qh > 9qhh > 9qhh0 > 9qhh0p", rows[0])
            assertEquals(
                gridRow(10, " b 34.50", " c 34.50", " f 34.50", " g 34.50", " u 34.50", " v 34.50", " y 34.50", " z 34.50"),
                rows[1],
            )
            assertEquals(
                gridRow(10, "   -118.12", "   -118.12", "   -118.12", "   -118.12", "   -118.12", "   -118.12", "   -118.12", "   -118.11"),
                rows[2],
            )
            assertEquals(" #9qhh0pb  block 7  34.496, -118.124  ~130 x 150 m", rows[9])
            assertEquals(" no coastline at this zoom", rows[10])
            assertEquals(gridKeys, rows[11])
        }
    }

    @Test fun anOddParentWithoutEightRowsFallsBackToCharacters() = runTest {
        runMosaicTest {
            // #b is one character, so its children are four columns by eight rows; nine lines leave
            // five for them, and half a grid is worse than none.
            setContentAndSnapshot { Screen(pi.copy(customGeohash = "bp"), IntSize(40, 9)) }
            type('g'.code)
            val rows = awaitSnapshot().lines().drop(1)
            assertEquals(" World > b", rows[0])
            assertEquals(gridRow(10, " p", " r", " x", " z"), rows[1])
            assertEquals(gridRow(10, " 5", " 7", " e", " g"), rows[5])
            assertEquals(" #bp  region 2  87, -174  ~61 x 630 km", rows[6])
            // Nothing claims there is no coastline: no map was tried at all.
            assertEquals(" near Qaanaaq", rows[7])
        }
    }

    @Test fun gridScrollsWithTheSelectionOnShortFrames() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi.copy(customGeohash = "cp"), IntSize(40, 6)) }
            type('g'.code)
            awaitSnapshot()
            repeat(6) { type(KeyboardEvent.Down) }
            // Eight rows, room for two: the view follows the selection ("c1", row seven), and the
            // selected cell's line keeps its place.
            assertEquals(
                listOf(
                    " World > c",
                    gridRow(10, " 4", " 6", " d", " f"),
                    gridRow(10, " 1", " 3", " 9", " c"),
                    " #c1  region 2  53, -129  ~750 x 630 km",
                    " near Prince Rupert",
                    gridKeysNarrow,
                ),
                awaitSnapshot().lines().drop(1),
            )
            type('p'.code)
            awaitSnapshot()
        }
        assertEquals(listOf("teleport=c1"), actions)
    }

    private suspend fun gridAfter(start: String, key: Int): Pair<String, String> {
        var rows = emptyList<String>()
        runMosaicTest {
            setContentAndSnapshot { Screen(pi.copy(customGeohash = start), IntSize(40, 9)) }
            type('g'.code)
            awaitSnapshot()
            type(key)
            rows = awaitSnapshot().lines().drop(1)
        }
        return rows.first() to rows.first { it.startsWith(" #") }
    }

    @Test fun movingPastAGridEdgeGoesToTheNeighbouringCell() = runTest {
        assertEquals(" World > 9 > 9q > 9q9 > 9q9n" to " #9q9nb  city 5  37.77, -122.32", gridAfter("9q8yz", KeyboardEvent.Right))
    }

    @Test fun eastAcrossTheAntimeridian() = runTest {
        assertEquals(" World > b" to " #bp  region 2  87, -174  ~61 x 630 km", gridAfter("zz", KeyboardEvent.Right))
    }

    @Test fun westAcrossTheAntimeridian() = runTest {
        assertEquals(" World > z" to " #zz  region 2  87, 174  ~61 x 630 km", gridAfter("bp", KeyboardEvent.Left))
    }

    @Test fun theWorldGridWrapsEastToWest() = runTest {
        assertEquals(" World" to " #b  region 1  68, -158", gridAfter("z", KeyboardEvent.Right))
    }

    @Test fun tInTheGridOpensTheCodeEntry() = runTest {
        // It is on the list, and a user who pressed it in the browser used to get nothing at all.
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type('g'.code)
            awaitSnapshot()
            type('t'.code)
            assertEquals(" Join a channel by geohash code", awaitSnapshot().lines()[1])
        }
        assertEquals(emptyList(), unhandled)
    }

    @Test fun wGoesBackToTheWholeWorldFromAnyDepth() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi.copy(customGeohash = "9q8yyssssss"), IntSize(85, 25)) }
            type('g'.code)
            assertTrue(awaitSnapshot().lines()[1].endsWith("> 9q8yyssss > 9q8yysssss"))
            type('w'.code)
            val rows = awaitSnapshot().lines().drop(1)
            assertEquals(" World", rows[0])
            assertEquals(" #b  region 1  68, -158  ~1,900 x 5,000 km", cell(rows))
        }
        assertEquals(emptyList(), unhandled)
    }

    @Test fun theDeepestCellCanStillBeJoinedAndLeftAtEverySize() = runTest {
        // Twelve characters is as far as Enter goes. Everything that gets a user back out has to
        // work down there, and the key line has to say so, or the browser is a hole to fall into.
        for (size in listOf(IntSize(240, 67), IntSize(85, 25), IntSize(40, 12))) {
            val picked = mutableStateListOf<String>()
            runMosaicTest {
                setContentAndSnapshot {
                    Column {
                        Text("picked ${picked.size}")
                        LocationsScreen(
                            state = pi.copy(customGeohash = "9q8yysssssss"),
                            size = size,
                            onSelectMesh = {},
                            onSelectChannel = {},
                            onToggleBookmark = {},
                            onTeleport = { picked += it },
                        )
                    }
                }
                type('g'.code)
                type(13) // Enter at the deepest level: there is no thirteenth character.
                var rows = awaitSnapshot().lines().drop(1)
                assertTrue(cell(rows).startsWith(" #9q8yysssssss  building 12"), "$size ${cell(rows)}")
                // It says so, rather than ignoring the key and looking stuck.
                assertTrue(rows.any { it.startsWith(" $DEEPEST_ZOOM") }, "$size ${rows.joinToString("|")}")
                // Enter is dropped from the key line where it would do nothing; the ways out are not.
                val keys = rows.first { it.startsWith(" p join") }
                assertTrue("Bksp out" in keys && "w world" in keys && "t code" in keys, "$size $keys")
                assertTrue("Enter in" !in keys, "$size $keys")
                // The deepest cell can be joined outright.
                type('p'.code)
                awaitSnapshot()
                // Backspace comes back up one level from it.
                type(127)
                assertTrue(cell(awaitSnapshot().lines().drop(1)).startsWith(" #9q8yyssssss  building 11"), "$size")
                // Delete does the same, for a terminal whose Backspace key sends that instead.
                type(KeyboardEvent.Delete)
                assertTrue(cell(awaitSnapshot().lines().drop(1)).startsWith(" #9q8yysssss  building 10"), "$size")
                // And one key is back at the whole world.
                type('w'.code)
                assertEquals(" World", awaitSnapshot().lines()[1], "$size")
            }
            assertEquals(listOf("9q8yysssssss"), picked.toList(), "$size")
        }
    }

    @Test fun escLeavesTheGrid() = runTest {
        runMosaicTest {
            setContentAndSnapshot { Screen(pi, IntSize(40, 9)) }
            type('g'.code)
            awaitSnapshot()
            type(27)
            assertEquals(" Locations", awaitSnapshot().lines()[1])
        }
        assertEquals(emptyList(), unhandled)
    }

    @Test fun consoleSafeNamesAreMapped() = runTest {
        val rows = render(AnsiLevel.NONE, pi.copy(bookmarkNames = mapOf("9q8yy" to "\u4E2D\u6587")), IntSize(40, 9), consoleSafe = true)
        assertEquals(pad("   * #9q8yy ??", "2 people ", 40), rows[4])
    }

    @Test fun theSmallestBodyKeepsThePromptAndTheError() = runTest {
        runMosaicTest {
            setContentAndSnapshot {
                Column {
                    LocationsScreen(pi, IntSize(40, 3), {}, {}, {}, {})
                    Text("^")
                }
            }
            type('t'.code)
            awaitSnapshot()
            type("9a")
            type(13)
            // The hint goes first; the error, the prompt and the title stay; nothing below row 3.
            assertEquals(listOf(" Join a channel by geohash code", "geohash> 9a ", " not a geohash", "^"), awaitSnapshot().lines())
        }
    }

    @Test fun theSmallestBodyListStaysInside() = runTest {
        val rows = render(AnsiLevel.NONE, pi, IntSize(40, 3))
        assertEquals(listOf(" Locations", pad(" > #mesh", "3 people ", 40), "   no location source"), rows)
    }

    @Test fun regionSizesReadAsDistances() {
        assertEquals("~1,900 x 5,000 km", cellSpan("b")) // one of the world's 32, near the pole: narrow
        assertEquals("~4,600 x 5,000 km", cellSpan("s")) // by the equator: nearly square
        assertEquals("~1,000 x 630 km", cellSpan("9q"))
        assertEquals("~120 x 160 km", cellSpan("9q8"))
        assertEquals("~3.8 x 4.8 km", cellSpan("9q8yy"))
        assertEquals("~970 x 610 m", cellSpan("9q8yyk"))
        assertEquals("~120 x 150 m", cellSpan("9q8yyk8"))
    }
}
