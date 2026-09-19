package dev.holgerendt.hanative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappableLocationTest {
    @Test
    fun looksLikeAddress_streetAndCity() {
        assertTrue(looksLikeMappableLocation("123 Main St, San Jose, CA"))
        assertTrue(looksLikeMappableLocation("Levi's Stadium, Santa Clara"))
        assertTrue(looksLikeMappableLocation("1 Infinite Loop"))
    }

    @Test
    fun looksLikeAddress_rejectsMeetingsAndUrls() {
        assertFalse(looksLikeMappableLocation("Online"))
        assertFalse(looksLikeMappableLocation("https://zoom.us/j/123"))
        assertFalse(looksLikeMappableLocation("meet.google.com/abc-defg"))
        assertFalse(looksLikeMappableLocation("Remote"))
        assertFalse(looksLikeMappableLocation(""))
        assertFalse(looksLikeMappableLocation(null))
    }

    @Test
    fun parseCoordinates_geoAndPair() {
        assertEquals(GeoPoint(37.3, -121.8), parseCoordinates("geo:37.3,-121.8"))
        assertEquals(GeoPoint(37.309, -121.789), parseCoordinates("37.309, -121.789"))
        assertNull(parseCoordinates("not coords"))
        assertNull(parseCoordinates("91.0, 0.0")) // invalid lat
    }

    @Test
    fun looksLikeAddress_acceptsExplicitCoords() {
        assertTrue(looksLikeMappableLocation("37.309, -121.789"))
        assertTrue(looksLikeMappableLocation("geo:37.309,-121.789"))
    }

    @Test
    fun normalizeLocationQuery_collapsesMultiline() {
        assertEquals(
            "4900 Marie P DeBartolo Way, Santa Clara, CA",
            normalizeLocationQuery("4900 Marie P DeBartolo Way\nSanta Clara, CA"),
        )
        assertNull(normalizeLocationQuery("  \n  "))
    }

    @Test
    fun geocodeQueryVariants_dropsVenuePrefixForStreetFallback() {
        val variants = geocodeQueryVariants(
            "KidTopia Indoor Play Center\n4620 Auto Mall Pkwy\nFremont",
        )
        assertTrue(variants.contains("KidTopia Indoor Play Center, 4620 Auto Mall Pkwy, Fremont"))
        assertTrue(variants.contains("4620 Auto Mall Pkwy, Fremont"))
        assertTrue(variants.contains("4620 Auto Mall Parkway, Fremont"))
        assertTrue(variants.contains("KidTopia Indoor Play Center, Fremont"))
    }

    @Test
    fun expandStreetAbbreviations_pkwy() {
        assertEquals(
            "4620 Auto Mall Parkway, Fremont",
            expandStreetAbbreviations("4620 Auto Mall Pkwy, Fremont"),
        )
        assertNull(expandStreetAbbreviations("123 Main Street"))
    }

    @Test
    fun parseNominatimResponse_readsLatLon() {
        val body = """[{"lat":"37.40","lon":"-121.97","display_name":"Test"}]"""
        assertEquals(GeoPoint(37.40, -121.97), LocationGeocoder.parseNominatimResponse(body))
        assertNull(LocationGeocoder.parseNominatimResponse("[]"))
    }
}
