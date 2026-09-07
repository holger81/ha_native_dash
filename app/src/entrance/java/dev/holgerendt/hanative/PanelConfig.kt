package dev.holgerendt.hanative

import dev.holgerendt.hanative.model.WidgetNode

object PanelConfig {
    const val IS_ENTRANCE = true
    const val PANEL_ID = "entrance"
    const val DISPLAY_NAME = "Entrance Wall"
    const val RECOVERY_DIR = "HA Native Entrance"
    const val CAMERA_POPUP = "#camera_alert"
    const val CAMERA_FLAG = ""
    const val USE_TABLET_MOTION = true
    const val MOTION_ENTITY = "input_boolean.entrance_tablet_motion"
    val PANEL_ALIASES = listOf("entrance", "entrance-wall", "entrance_wall")
    const val DEFAULT_DISPLAY_OFF_ENTITY = ""
    const val DEFAULT_DISPLAY_BRIGHTNESS_ENTITY = ""
    const val DEFAULT_DISPLAY_ILLUMINANCE_ENTITY = ""
    const val ALLOW_CALENDAR_CREATE = false
    val DRAWER_ITEMS = listOf(
        DrawerDestination("Camera", CAMERA_POPUP),
        DrawerDestination("Changelog", "#changelog"),
        DrawerDestination("Settings", "#settings"),
    )

    fun wallCameras(go2rtcUrl: String): List<WidgetNode> = listOf(
        WidgetNode(
            type = "camera",
            name = "Front Door",
            entity = "camera.reolink_video_doorbell_poe_fluent",
            streamServer = go2rtcUrl,
            streamName = "frontdoor_sub",
            muted = true,
        ),
        WidgetNode(
            type = "camera",
            name = "Entrance",
            entity = "camera.entrance_fisheye_fluent",
            streamServer = go2rtcUrl,
            streamName = "entrance_fisheye_sub",
            muted = true,
        ),
    )
}
