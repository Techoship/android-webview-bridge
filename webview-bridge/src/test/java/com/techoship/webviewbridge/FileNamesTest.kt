package com.techoship.webviewbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    private val extensions = mapOf("application/pdf" to "pdf", "text/csv" to "csv", "image/png" to "png")
    private fun sanitize(raw: String?, mime: String? = null) = FileNames.sanitize(raw, mime) { extensions[it] }

    @Test fun keepsAnOrdinaryName() = assertEquals("report.pdf", sanitize("report.pdf", "application/pdf"))

    @Test fun dropsDirectories() {
        assertEquals("passwd", sanitize("../../etc/passwd"))
        assertEquals("invoice.pdf", sanitize("C:\\Users\\me\\invoice.pdf"))
    }

    @Test fun replacesReservedCharacters() = assertEquals("a_b_c_d.txt", sanitize("a:b*c?d.txt"))

    @Test fun removesControlCharactersAndCollapsesSpaces() = assertEquals("my _ file.txt", sanitize("my \u0000  file.txt"))

    @Test fun trimsLeadingAndTrailingDots() = assertEquals("env", sanitize(".env."))

    @Test fun fallsBackWhenNothingUsableIsLeft() {
        assertEquals("download", sanitize(null))
        assertEquals("download", sanitize("   "))
        assertEquals("download.pdf", sanitize("???", "application/pdf"))
    }

    @Test fun addsAnExtensionFromTheMimeType() {
        assertEquals("export.csv", sanitize("export", "text/csv"))
        assertEquals("export.csv", sanitize("export", "text/csv; charset=utf-8"))
    }

    @Test fun keepsAnExistingExtensionEvenIfTheMimeTypeDiffers() = assertEquals("photo.jpeg", sanitize("photo.jpeg", "image/png"))

    @Test fun leavesTheNameAloneForAnUnknownMimeType() = assertEquals("data", sanitize("data", "application/x-unknown"))

    @Test fun capsTheLengthButKeepsTheExtension() {
        val name = sanitize("a".repeat(300) + ".pdf")
        assertEquals(FileNames.MAX_LENGTH, name.length)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test fun findsTheExtension() {
        assertEquals("gz", FileNames.extensionOf("archive.tar.gz"))
        assertNull(FileNames.extensionOf("README"))
        assertNull(FileNames.extensionOf(".hidden"))
        assertNull(FileNames.extensionOf("trailing."))
    }

    @Test fun numbersDuplicateNames() {
        val taken = setOf("report.pdf", "report (1).pdf")
        assertEquals("report (2).pdf", FileNames.unique("report.pdf") { it in taken })
        assertEquals("notes", FileNames.unique("notes") { false })
        assertEquals("notes (1)", FileNames.unique("notes") { it == "notes" })
    }
}
