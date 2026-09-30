package com.bitchat.tui

import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HeaderTest {
    @Test fun showsNicknameAndPeerCount() = runTest {
        runMosaicTest {
            val frame = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = 40) }
            assertTrue(frame.lines().first().contains("anon1234"))
            assertTrue(frame.lines().first().contains("3 peers"))
        }
    }

    @Test fun proofOfWorkShowsInTheHeaderWhileItIsOn() = runTest {
        // The terminal's answer to the Compose apps' shield: without it nothing on screen says the
        // setting is doing anything at all.
        assertEquals(" anon1234                    pow16, 3 peers ", headerLine("anon1234", 3, 44, powBits = 16))
        assertEquals(" anon1234                           3 peers ", headerLine("anon1234", 3, 44))
    }

    @Test fun theProofOfWorkMarkIsTheFirstThingTheHeaderDrops() = runTest {
        // The peer count is what the header is for; the DM count and then the difficulty go first.
        assertEquals("2 DM, 3 peers ", headerLine("anon1234", 3, 14, unreadDms = 2, powBits = 16))
        assertEquals("3 peers ", headerLine("anon1234", 3, 8, unreadDms = 2, powBits = 16))
    }

    @Test fun singlePeerIsSingular() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 1, width = 40) }.lines().first()
            assertTrue(line.contains("1 peer"), line)
            assertFalse(line.contains("1 peers"), line)
        }
    }

    @Test fun zeroPeersIsPlural() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 0, width = 40) }.lines().first()
            assertTrue(line.contains("0 peers"), line)
        }
    }

    @Test fun headerIsOneLineExactlyWidthColumnsWide() = runTest {
        runMosaicTest {
            val frame = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = 40) }
            assertEquals(1, frame.lines().size, frame)
            assertEquals(40, frame.lines().first().length, "[${frame.lines().first()}]")
            assertTrue(frame.lines().first().trimEnd().endsWith("3 peers"), frame)
        }
    }

    @Test fun longNicknameIsTruncatedToWidthKeepingPeerCount() = runTest {
        runMosaicTest {
            val nickname = "averyveryveryverylongnicknamethatdoesnotfit"
            val line = setContentAndSnapshot { Header(nickname = nickname, peerCount = 12, width = 30) }.lines().first()
            assertEquals(30, line.length, "[$line]")
            assertTrue(line.contains("12 peers"), line)
            assertTrue(line.contains("averyvery"), line)
            assertTrue(line.contains("..."), line)
            assertFalse(line.contains(nickname), line)
        }
    }

    @Test fun tinyWidthKeepsThePeerNumberAndDropsTheWord() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = 6) }.lines().first()
            assertEquals("     3", line)
        }
    }

    @Test fun numberWiderThanHeaderShowsItsRightmostDigits() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 12345, width = 3) }.lines().first()
            assertEquals("345", line)
        }
    }

    @Test fun narrowWidthDropsNicknameButKeepsPeerCountWhole() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = 13) }.lines().first()
            assertEquals("     3 peers ", line)
        }
    }

    @Test fun nicknameThatFitsExactlyHasNoEllipsis() = runTest {
        runMosaicTest {
            // " anon1234" + " " + "3 peers " is 18 columns.
            val line = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = 18) }.lines().first()
            assertEquals(" anon1234 3 peers ", line)
        }
    }

    @Test fun nicknameOneColumnTooLongIsEllipsized() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = 17) }.lines().first()
            assertEquals(" anon... 3 peers ", line)
        }
    }

    @Test fun emptyNicknameShowsOnlyPeerCount() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "", peerCount = 3, width = 40) }.lines().first()
            assertEquals(40, line.length, "[$line]")
            assertEquals("3 peers", line.trim())
        }
    }

    @Test fun zeroWidthRendersNothing() = runTest {
        runMosaicTest {
            assertEquals("", setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = 0) })
        }
    }

    @Test fun negativeWidthIsTreatedAsZero() = runTest {
        runMosaicTest {
            assertEquals("", setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = -5) })
        }
    }

    @Test fun wideNicknameStaysWithinWidth() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "你好世界你好世界你好世界", peerCount = 3, width = 20) }.lines().first()
            // " 你好世" is 7 cells, "..." 3, padding 2, "3 peers " 8: 20.
            assertEquals(" 你好世...  3 peers ", line)
        }
    }

    @Test fun wideNicknameThatFitsIsPaddedByCells() = runTest {
        runMosaicTest {
            val line = setContentAndSnapshot { Header(nickname = "你好", peerCount = 3, width = 40) }.lines().first()
            // " 你好" is 5 cells, padding 27, "3 peers " 8: 40.
            assertEquals(" 你好" + " ".repeat(27) + "3 peers ", line)
        }
    }

    @Test fun unreadDmsComeBeforeThePeerCount() {
        // " anon1234" 9 cells, padding 17, "2 DM, 3 peers " 14: 40.
        assertEquals(" anon1234" + " ".repeat(17) + "2 DM, 3 peers ", headerLine("anon1234", 3, 40, unreadDms = 2))
    }

    @Test fun unreadDmMarkerIsDroppedBeforeThePeerCount() {
        assertEquals("     3 peers ", headerLine("anon1234", 3, 13, unreadDms = 2))
    }

    @Test fun headerIsDrawnInTheThemeAccentColours() = runTest {
        runMosaicTest(MosaicSnapshots) {
            val mosaic = setContentAndSnapshot { Header(nickname = "anon1234", peerCount = 3, width = 40) }
            val ansi = mosaic.draw().render(AnsiLevel.ANSI16, false)
            // A [Bar]: the dark theme's black on bright green.
            assertTrue(ansi.startsWith("\u001B[30;102m"), ansi.replace("\u001B", "ESC"))
        }
    }
}
