package dev.holgerendt.hanative.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The bundled Flux fill graph is tuned against real covers; these values are the
 * ones that stopped the invented cream pads, so pin them.
 */
class AlbumOutpaintWorkflowAssetTest {
    private val workflow by lazy {
        val file = File("src/main/assets/${ComfyUiOutpaintClient.WORKFLOW_ASSET}")
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun padsMatchClientConstants() {
        val pad = workflow["44"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals(ComfyUiOutpaintClient.OUTPAINT_PAD_LEFT, pad["left"]!!.jsonPrimitive.content.toInt())
        assertEquals(ComfyUiOutpaintClient.OUTPAINT_PAD_TOP, pad["top"]!!.jsonPrimitive.content.toInt())
        assertEquals(ComfyUiOutpaintClient.OUTPAINT_PAD_RIGHT, pad["right"]!!.jsonPrimitive.content.toInt())
        assertEquals(ComfyUiOutpaintClient.OUTPAINT_PAD_BOTTOM, pad["bottom"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun featheringStaysZeroSoTheCoverEdgeIsNeverRepainted() {
        val pad = workflow["44"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals(0, pad["feathering"]!!.jsonPrimitive.content.toInt())
    }
}
