package com.bitchat.design

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class StringResourcesTest {
    // Compose resources show a raw line break and its indentation on screen.
    @Test
    fun `no string resource is written across lines`() {
        val file = File(System.getProperty("user.dir"), "src/commonMain/composeResources/values/strings.xml")
        assertTrue(file.isFile, "strings.xml not found at ${file.absolutePath}")

        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).getElementsByTagName("string")
        val offending = (0 until strings.length)
            .map { strings.item(it) }
            .filter { node -> node.textContent.any { it == '\n' || it == '\r' } }
            .map { it.attributes.getNamedItem("name").nodeValue }

        assertTrue(offending.isEmpty(), "strings with a raw line break (${offending.size}): $offending")
    }
}
