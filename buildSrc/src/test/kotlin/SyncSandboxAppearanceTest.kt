import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLStreamException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncSandboxAppearanceTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun rejectsInternalAndExternalDoctypes() {
        val builder = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
        val declarations =
            listOf(
                """<!DOCTYPE application [<!ENTITY value "example">]>""",
                """<!DOCTYPE application SYSTEM "file:///not-read.dtd">""")
        for ((index, declaration) in declarations.withIndex()) {
            val file = directory.newFile("blocked-$index.xml")
            file.writeText("$declaration<application/>")
            assertThrows(XMLStreamException::class.java) {
                readAppearanceXml(file.toPath(), builder)
            }
        }
    }

    @Test
    fun preservesTheXmlEncodingAndDocumentComments() {
        val file = directory.newFile("appearance.xml")
        file.writeText(
            """<?xml version="1.0" encoding="UTF-16"?><!-- <!DOCTYPE example> --><application name="café"/>""",
            Charsets.UTF_16)
        val builder = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
        val document = readAppearanceXml(file.toPath(), builder)

        assertEquals(" <!DOCTYPE example> ", document.firstChild.nodeValue)
        assertEquals("café", document.documentElement.getAttribute("name"))
    }
}
