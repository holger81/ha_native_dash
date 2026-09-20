package dev.holgerendt.hanative.ui

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutpaintFileRevisionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun fluxFlagChangesRevisionWhenLengthAndMtimeMatch() {
        val file = tmp.newFile("pad.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val local = outpaintFileRevision(file, fluxComplete = false)
        val flux = outpaintFileRevision(file, fluxComplete = true)
        assertNotEquals(local, flux)
        assertEquals(local, outpaintFileRevision(file, fluxComplete = false))
    }
}
