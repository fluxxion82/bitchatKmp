package com.bitchat.tui

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class BarTest {
    @Test fun barPadsToTheFullWidth() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Bar(" hi", width = 10) }.lines().single()
            assertEquals(" hi       ", line)
        }
    }

    @Test fun barCutsTextWiderThanTheWidth() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Bar("abcdefghijkl", width = 5) }.lines().single()
            assertEquals("abcde", line)
        }
    }

    @Test fun barCountsWideCharactersAsTwoCells() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Bar("中文字", width = 5) }.lines().single()
            assertEquals("中文 ", line) // Two wide characters, then one space of padding.
        }
    }

    @Test fun barIsDrawnInTheThemeAccentColoursAcrossItsWholeWidth() = runTest {
        runMosaicTest(MosaicSnapshots) {
            val ansi = setContentAndSnapshot { Bar("hi", width = 10) }.draw().render(AnsiLevel.ANSI16, false)
            // The dark theme's onPrimary (black, 30) on its primary (bright green, 102), and the
            // padding is styled too, so all ten cells survive.
            assertEquals("\u001B[30;102mhi        \u001B[0m", ansi, ansi.replace("\u001B", "ESC"))
        }
    }
}
