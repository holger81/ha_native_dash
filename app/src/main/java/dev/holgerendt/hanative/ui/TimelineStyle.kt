package dev.holgerendt.hanative.ui

import androidx.compose.ui.graphics.Color
import dev.holgerendt.hanative.data.HaCalendarEvent

/**
 * Icon and accent colors aligned with valentinfrlch/llmvision-card `src/labels.js`.
 */
private data class CategoryStyle(
    val color: Color,
    val labelIcons: Map<String, String>,
)

private val categoryStyles = mapOf(
    "person" to CategoryStyle(
        color = Color(0xFF3B82F6),
        labelIcons = mapOf("person" to "mdi:walk"),
    ),
    "vehicle" to CategoryStyle(
        color = Color(0xFF64748B),
        labelIcons = mapOf(
            "car" to "mdi:car",
            "truck" to "mdi:truck",
            "van" to "mdi:van-utility",
            "bus" to "mdi:bus",
            "bicycle" to "mdi:bike",
            "motorcycle" to "mdi:motorbike",
        ),
    ),
    "delivery" to CategoryStyle(
        color = Color(0xFFEA580C),
        labelIcons = mapOf("delivery" to "mdi:package-variant-closed"),
    ),
    "animal" to CategoryStyle(
        color = Color(0xFF00DD51),
        labelIcons = mapOf(
            "dog" to "mdi:dog",
            "cat" to "mdi:cat",
            "bird" to "mdi:bird",
            "animal" to "mdi:paw",
        ),
    ),
    "entity" to CategoryStyle(
        color = Color(0xFF8B5CF6),
        labelIcons = mapOf(
            "door" to "mdi:door-closed",
            "camera" to "mdi:cctv",
            "sensor" to "mdi:access-point",
            "light" to "mdi:lightbulb",
            "key" to "mdi:key",
            "lock" to "mdi:lock",
            "alarm" to "mdi:alarm-light",
        ),
    ),
    "nature" to CategoryStyle(
        color = Color(0xFF16A34A),
        labelIcons = mapOf(
            "tree" to "mdi:tree",
            "plant" to "mdi:flower",
        ),
    ),
)

private val defaultTimelineStyle = "mdi:motion-sensor" to Color(0xFF757575)

fun timelineEventStyle(event: HaCalendarEvent): Pair<String, Color> {
    val category = event.category?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    val label = event.label?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    if (category != null) {
        val style = categoryStyles[category]
        if (style != null) {
            if (label != null) {
                val icon = style.labelIcons[label]
                if (icon != null) return icon to style.color
            }
            val categoryIcon = style.labelIcons.values.firstOrNull()
            if (categoryIcon != null) return categoryIcon to style.color
        }
    }

    if (label != null) {
        for ((_, style) in categoryStyles) {
            val icon = style.labelIcons[label]
            if (icon != null) return icon to style.color
        }
    }

    return inferTimelineStyleFromText(
        listOfNotNull(event.category, event.label, event.summary).joinToString(" "),
    )
}

private fun inferTimelineStyleFromText(text: String): Pair<String, Color> {
    val haystack = text.lowercase()
    return when {
        haystack.contains("delivery") ||
            haystack.contains("parcel") ||
            haystack.contains("package") ||
            haystack.contains("mail") ||
            haystack.contains("mailbox") ||
            haystack.contains("courier") ->
            "mdi:package-variant-closed" to Color(0xFFEA580C)
        haystack.contains("door") || haystack.contains("entrance") || haystack.contains("entry") ->
            "mdi:door-closed" to Color(0xFF8B5CF6)
        haystack.contains("truck") || haystack.contains("van") ->
            "mdi:truck" to Color(0xFF64748B)
        haystack.contains("bus") ->
            "mdi:bus" to Color(0xFF64748B)
        haystack.contains("bike") || haystack.contains("bicycle") || haystack.contains("cyclist") ->
            "mdi:bike" to Color(0xFF64748B)
        haystack.contains("motorcycle") || haystack.contains("motorbike") ->
            "mdi:motorbike" to Color(0xFF64748B)
        haystack.contains("car") || haystack.contains("vehicle") || haystack.contains("suv") ->
            "mdi:car" to Color(0xFF64748B)
        haystack.contains("dog") ->
            "mdi:dog" to Color(0xFF00DD51)
        haystack.contains("cat") ->
            "mdi:cat" to Color(0xFF00DD51)
        haystack.contains("bird") ->
            "mdi:bird" to Color(0xFF00DD51)
        haystack.contains("person") || haystack.contains("people") || haystack.contains("human") ->
            "mdi:walk" to Color(0xFF3B82F6)
        else -> defaultTimelineStyle
    }
}
