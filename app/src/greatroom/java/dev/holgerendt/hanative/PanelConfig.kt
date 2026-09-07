package dev.holgerendt.hanative

import dev.holgerendt.hanative.model.WidgetNode

object PanelConfig {
    const val IS_ENTRANCE = false
    const val PANEL_ID = "greatroom"
    const val DISPLAY_NAME = "Greatroom Wall"
    const val RECOVERY_DIR = "HA Native"
    const val CAMERA_POPUP = "#camerafront_view"
    const val CAMERA_FLAG = "input_boolean.greatroom_wall_camera"
    const val USE_TABLET_MOTION = false
    const val MOTION_ENTITY = ""
    val PANEL_ALIASES = listOf("greatroom", "greatroom-wall", "greatroom_wall")
    const val DEFAULT_DISPLAY_OFF_ENTITY = "switch.uc_display"
    const val DEFAULT_DISPLAY_BRIGHTNESS_ENTITY = "number.uc_display_brightness"
    const val DEFAULT_DISPLAY_ILLUMINANCE_ENTITY = "sensor.secondary_living_room_switch_illuminance"
    const val ALLOW_CALENDAR_CREATE = true
    val DRAWER_ITEMS = listOf(
        DrawerDestination("Weather", "#weather"),
        DrawerDestination("Power", "#power"),
        DrawerDestination("Presence", "#presence"),
        DrawerDestination("Cars", "#bil"),
        DrawerDestination("Staubinator", "#staubinator"),
        DrawerDestination("Camera", CAMERA_POPUP),
        DrawerDestination("Music", "#music"),
        DrawerDestination("Changelog", "#changelog"),
        DrawerDestination("Settings", "#settings"),
    )

    fun wallCameras(go2rtcUrl: String): List<WidgetNode> = listOf(
        WidgetNode(
            type = "camera",
            name = "Front door",
            entity = "camera.reolink_video_doorbell_poe_fluent",
            streamServer = go2rtcUrl,
            streamName = "frontdoor_sub",
            muted = true,
        ),
        WidgetNode(
            type = "camera",
            name = "Garage",
            entity = "camera.garagefront_2",
            streamServer = go2rtcUrl,
            streamName = "garagefront_sub",
            muted = true,
        ),
    )
}
