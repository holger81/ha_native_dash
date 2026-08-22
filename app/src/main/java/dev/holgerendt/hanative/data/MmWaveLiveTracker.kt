package dev.holgerendt.hanative.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

data class MmWaveSlot(val x: Int, val y: Int, val z: Int)

data class MmWaveLiveTargets(
    val count: Int = 0,
    val slots: Map<Int, MmWaveSlot> = emptyMap(),
)

data class MmWaveTargetEvent(
    val count: Int,
    val slotUpdates: Map<Int, MmWaveSlot>,
)

object MmWaveLiveTracker {
    const val DEVICE_IEEE = "0c:2a:6f:ff:fe:d5:58:af"
    const val EVENT_COMMAND = "mmwave_target_info"

    fun parseZhaEvent(data: JsonObject): MmWaveTargetEvent? {
        if (data["command"]?.jsonPrimitive?.contentOrNull != EVENT_COMMAND) return null
        val ieee = data["device_ieee"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
        if (ieee != DEVICE_IEEE) return null
        val args = data["args"]?.jsonObject ?: return null
        return parseArgs(args)
    }

    fun merge(current: MmWaveLiveTargets, event: MmWaveTargetEvent): MmWaveLiveTargets {
        if (event.count <= 0 && event.slotUpdates.isEmpty()) {
            return MmWaveLiveTargets()
        }
        val slots = current.slots.toMutableMap()
        event.slotUpdates.forEach { (slot, pos) -> slots[slot] = pos }
        val count = event.count.coerceIn(0, 4).coerceAtLeast(slots.keys.maxOrNull() ?: 0)
        (count + 1..4).forEach { slots.remove(it) }
        return MmWaveLiveTargets(count = count, slots = slots)
    }

    private fun parseArgs(args: JsonObject): MmWaveTargetEvent? {
        val count = args.number("target_num")?.roundToInt()?.coerceIn(0, 4) ?: return null
        val fromList = parseTargetList(args["targets"])
        val fromFlat = parseFlatTargets(args)
        val fromLegacy = parseLegacyTarget(args)
        val updates = linkedMapOf<Int, MmWaveSlot>()
        fromList.forEach { (slot, pos) -> updates[slot] = pos }
        fromFlat.forEach { (slot, pos) -> updates[slot] = pos }
        fromLegacy?.let { (slot, pos) -> updates[slot] = pos }
        if (updates.isEmpty() && count <= 0) return null
        return MmWaveTargetEvent(count = count, slotUpdates = updates)
    }

    private fun parseTargetList(element: kotlinx.serialization.json.JsonElement?): Map<Int, MmWaveSlot> {
        val array = element as? JsonArray ?: return emptyMap()
        val out = linkedMapOf<Int, MmWaveSlot>()
        array.forEachIndexed { index, item ->
            val obj = item.jsonObject
            val slot = obj.number("id")?.roundToInt()?.takeIf { it in 1..4 } ?: (index + 1)
            val x = obj.number("x")?.roundToInt() ?: return@forEachIndexed
            val y = obj.number("y")?.roundToInt() ?: return@forEachIndexed
            val z = obj.number("z")?.roundToInt() ?: 0
            out[slot] = MmWaveSlot(x, y, z)
        }
        return out
    }

    private fun parseFlatTargets(args: JsonObject): Map<Int, MmWaveSlot> {
        val out = linkedMapOf<Int, MmWaveSlot>()
        for (slot in 1..4) {
            val x = args.number("target_${slot}_x")?.roundToInt() ?: continue
            val y = args.number("target_${slot}_y")?.roundToInt() ?: continue
            val z = args.number("target_${slot}_z")?.roundToInt() ?: 0
            if (x == 0 && y == 0 && z == 0) continue
            out[slot] = MmWaveSlot(x, y, z)
        }
        return out
    }

    private fun parseLegacyTarget(args: JsonObject): Pair<Int, MmWaveSlot>? {
        val x = args.number("x")?.roundToInt() ?: return null
        val y = args.number("y")?.roundToInt() ?: return null
        val z = args.number("z")?.roundToInt() ?: 0
        val slot = args.number("id")?.roundToInt()?.takeIf { it in 1..4 } ?: 1
        return slot to MmWaveSlot(x, y, z)
    }

    private fun JsonObject.number(key: String): Double? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.intOrNull?.toDouble() ?: primitive.contentOrNull?.toDoubleOrNull()
    }
}
