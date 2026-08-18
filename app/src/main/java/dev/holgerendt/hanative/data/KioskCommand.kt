package dev.holgerendt.hanative.data

import org.json.JSONObject

/** Remote wall commands, aligned with Fully Kiosk / WallPanel `loadUrl` style. */
sealed class KioskCommand {
    data class Navigate(val hash: String) : KioskCommand()
    data object Home : KioskCommand()
    data class MoreInfo(val entityId: String) : KioskCommand()
    data object Sleep : KioskCommand()
    data object Wake : KioskCommand()
}

object KioskCommands {
    const val EVENT = "ha_native_dash"
    const val CAMERA_POPUP = "#camerafront_view"
    const val CAMERA_FLAG = "input_boolean.greatroom_wall_camera"
    const val PANEL_ID = "greatroom"

    private val popupAliases = mapOf(
        "camera" to CAMERA_POPUP,
        "cameras" to CAMERA_POPUP,
        "doorbell" to CAMERA_POPUP,
        "front" to CAMERA_POPUP,
        "frontdoor" to CAMERA_POPUP,
        "video" to CAMERA_POPUP,
        "camerafront" to CAMERA_POPUP,
        "camerafront_view" to CAMERA_POPUP,
        "power" to "#power",
        "weather" to "#weather",
        "vacuum" to "#staubinator",
        "staubinator" to "#staubinator",
        "cars" to "#bil",
        "car" to "#bil",
        "bil" to "#bil",
        "settings" to "#settings",
    )

    fun fromParams(params: Map<String, String>): KioskCommand? {
        val cmd = first(params, "cmd", "command", "action").orEmpty()
        val path = first(params, "path", "hash", "url", "view")
        val entity = first(params, "entity_id", "entity")
        return from(cmd, path, entity)
    }

    fun fromJson(text: String): KioskCommand? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val nested = obj.optJSONObject("data")
        val params = mutableMapOf<String, String>()
        flatten(obj, params)
        nested?.let { flatten(it, params) }
        return fromParams(params)
    }

    fun from(cmd: String, path: String?, entity: String?): KioskCommand? {
        val command = cmd.trim().lowercase()
        val resolved = resolvePath(path)
        return when {
            command in setOf("camera", "show_camera", "doorbell", "video") ->
                KioskCommand.Navigate(CAMERA_POPUP)
            command in setOf("sleep", "screen_off", "screensaver") ->
                KioskCommand.Sleep
            command in setOf("wake", "screen_on") ->
                KioskCommand.Wake
            command in setOf("home", "close", "dismiss", "clear", "clearurl") ->
                KioskCommand.Home
            command in setOf("more_info", "more-info", "moreinfo") -> {
                val id = entity?.trim().orEmpty()
                if (id.isBlank()) null else KioskCommand.MoreInfo(id)
            }
            command in setOf("navigate", "loadurl", "load_url", "open", "url", "goto", "") -> {
                when {
                    resolved != null -> KioskCommand.Navigate(resolved)
                    !path.isNullOrBlank() -> KioskCommand.Navigate("#${path.trim().removePrefix("#")}")
                    command.isBlank() -> KioskCommand.Home
                    else -> resolvePath(command)?.let { KioskCommand.Navigate(it) }
                }
            }
            else -> resolvePath(command)?.let { KioskCommand.Navigate(it) }
                ?: resolved?.let { KioskCommand.Navigate(it) }
        }
    }

    fun resolvePath(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val stripped = value
            .removePrefix("ha-native://")
            .removePrefix("hanative://")
            .substringAfter("://")
            .trim()
            .ifBlank { value }
        val key = stripped.removePrefix("#").removePrefix("/").lowercase()
        if (key in setOf("home", "", "close", "dismiss", "clear")) return null
        return popupAliases[key] ?: "#${stripped.removePrefix("#").removePrefix("/")}"
    }

    fun panelAllowed(params: Map<String, String>): Boolean {
        val panel = first(params, "panel", "browser_id", "device") ?: return true
        return panel.equals(PANEL_ID, ignoreCase = true) ||
            panel.equals("greatroom-wall", ignoreCase = true) ||
            panel.equals("greatroom_wall", ignoreCase = true)
    }

    private fun first(params: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> params[key]?.trim()?.takeIf { it.isNotEmpty() } }

    private fun flatten(obj: JSONObject, into: MutableMap<String, String>) {
        obj.keys().forEach { key ->
            val value = obj.opt(key) ?: return@forEach
            if (value is JSONObject || value === JSONObject.NULL) return@forEach
            val text = value.toString().trim()
            if (text.isNotEmpty()) into[key] = text
        }
    }
}
