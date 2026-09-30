package com.bitchat.design.settings

import androidx.compose.ui.graphics.Color
import com.bitchat.domain.tor.model.TorState
import kotlin.test.Test
import kotlin.test.assertEquals

class TorStatusIndicatorTest {
    @Test
    fun `error state stays red when stale running progress says complete`() {
        assertEquals(
            Color(0xFFFF3B30),
            torStatusIndicatorColor(
                running = true,
                bootstrapPercent = 100,
                state = TorState.ERROR,
                isDark = false,
            ),
        )
    }
}
