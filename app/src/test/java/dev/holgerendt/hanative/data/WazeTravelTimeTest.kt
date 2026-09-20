package dev.holgerendt.hanative.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WazeTravelTimeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun toWazeCoord_formatsLatLng() {
        assertEquals("37.4,-121.9", GeoPoint(37.4, -121.9).toWazeCoord())
    }

    @Test
    fun parseWazeTravelMinutes_picksShortestRoute() {
        val body = buildJsonObject {
            putJsonArray("routes") {
                add(buildJsonObject {
                    put("duration", 31.65)
                    put("distance", 23.7)
                    put("name", "longer")
                })
                add(buildJsonObject {
                    put("duration", 25.75)
                    put("distance", 22.8)
                    put("name", "shortest")
                })
            }
        }
        assertEquals(26, parseWazeTravelMinutes(body))
    }

    @Test
    fun parseWazeTravelMinutes_unwrapsResponseKey() {
        val body = buildJsonObject {
            putJsonObject("response") {
                putJsonArray("routes") {
                    add(buildJsonObject { put("duration", 16.15) })
                }
            }
        }
        assertEquals(16, parseWazeTravelMinutes(body))
    }

    @Test
    fun parseWazeTravelMinutes_stringDuration() {
        val body = json.parseToJsonElement(
            """{"routes":[{"duration":"12.4","name":"a"}]}""",
        )
        assertEquals(12, parseWazeTravelMinutes(body))
    }

    @Test
    fun parseWazeTravelMinutes_emptyOrMissing() {
        assertNull(parseWazeTravelMinutes(null))
        assertNull(parseWazeTravelMinutes(buildJsonObject { }))
        assertNull(parseWazeTravelMinutes(buildJsonObject {
            putJsonArray("routes") {}
        }))
    }

    @Test
    fun formatTravelDriveLabel() {
        assertEquals("~28 min drive", formatTravelDriveLabel(28))
        assertEquals("~1 min drive", formatTravelDriveLabel(1))
        assertEquals("~1 min drive", formatTravelDriveLabel(0))
    }
}
