package com.wichat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun stripsPathTraversal() {
        assertEquals("passwd", FileNames.safe("../../etc/passwd"))
        assertEquals("evil.exe", FileNames.safe("..\\..\\Windows\\evil.exe"))
        assertEquals("databases", FileNames.safe("../databases"))
    }

    @Test
    fun removesHiddenAndControlCharacters() {
        assertEquals("bashrc", FileNames.safe(".bashrc"))
        assertEquals("report.pdf", FileNames.safe("rep\u0000ort.pdf"))
        assertEquals("ab.txt", FileNames.safe("a<b>.txt"))
    }

    @Test
    fun fallsBackWhenNothingLeft() {
        assertEquals("file", FileNames.safe(null))
        assertEquals("file", FileNames.safe("../"))
        assertEquals("file", FileNames.safe("..."))
    }

    @Test
    fun keepsExtensionWhenTruncating() {
        val name = FileNames.safe("a".repeat(500) + ".pdf")
        assertTrue(name.length <= 120)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun formatsSizesAndDurations() {
        assertEquals("512 B", FileNames.humanSize(512))
        assertEquals("1.5 MB", FileNames.humanSize(1_572_864))
        assertEquals("1:05", FileNames.duration(65_400))
    }
}
