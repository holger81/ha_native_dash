package dev.holgerendt.hanative.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicDisplayTitleTest {
    @Test fun removesArtistPrefixDespiteSpacingDifferences() {
        assertEquals("Challenger", musicDisplayTitle("Luca Fogale - Challenger", "LucaFogale"))
    }

    @Test fun preservesSeparatorsWithinTrackName() {
        assertEquals("Song - Live", musicDisplayTitle("Artist — Song - Live", "artist"))
    }

    @Test fun preservesUnrelatedPrefixesAndUnknownArtists() {
        assertEquals("Part One - Finale", musicDisplayTitle("Part One - Finale", "Artist"))
        assertEquals("Artist - Song", musicDisplayTitle("Artist - Song", ""))
        assertEquals("Artist's Song", musicDisplayTitle("Artist's Song", "Artist"))
    }
}
