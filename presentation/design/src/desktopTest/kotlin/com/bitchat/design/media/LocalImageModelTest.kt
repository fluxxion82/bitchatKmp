package com.bitchat.design.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalImageModelTest {

    @Test
    fun `normalizes absolute local paths and preserves local file URIs`() {
        assertEquals("file:///private/images/photo.png", localImageModel("/private/images/photo.png"))
        assertEquals("file:///private/images/photo.png", localImageModel("file:///private/images/photo.png"))
        assertEquals("file:///private/My Image.png", localImageModel("/private/My Image.png"))
    }

    @Test
    fun `rejects non-local image models`() {
        listOf(
            "http://example.com/image.png",
            "https://example.com/image.png",
            "HTTPS://example.com/image.png",
            "data:image/png;base64,abc",
            "content://media/external/images/1",
            "images/photo.png",
            "",
            " \t\n",
            "file://evil.com/x",
        ).forEach { raw ->
            assertNull(localImageModel(raw), raw)
        }
    }
}
