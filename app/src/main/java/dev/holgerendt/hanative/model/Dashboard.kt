package dev.holgerendt.hanative.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class DashboardFile(
    val version: Int = 1,
    val source: String? = null,
    val home: HomeDashboard,
    val entities: List<String> = emptyList(),
)

@Serializable
data class HomeDashboard(
    val title: String = "Home",
    val people: List<WidgetNode> = emptyList(),
    val header: List<WidgetNode> = emptyList(),
    val chips: WidgetNode? = null,
    val calendar: WidgetNode? = null,
    val timeline: WidgetNode? = null,
    val rooms: List<WidgetNode> = emptyList(),
    val popups: List<PopupNode> = emptyList(),
)

@Serializable
data class PopupNode(
    val type: String = "popup",
    val name: String? = null,
    val icon: String? = null,
    val hash: String? = null,
    val accent: String? = null,
    val cards: List<WidgetNode> = emptyList(),
)

@Serializable
data class TabNode(
    val title: String? = null,
    val icon: String? = null,
    val cards: List<WidgetNode> = emptyList(),
)

@Serializable
data class WidgetNode(
    val type: String,
    val name: String? = null,
    val icon: String? = null,
    val entity: String? = null,
    @SerialName("entity_ids") val entityIds: List<String>? = null,
    @SerialName("stream_server") val streamServer: String? = null,
    @SerialName("stream_name") val streamName: String? = null,
    @SerialName("camera_view") val cameraView: String? = null,
    @SerialName("card_type") val cardType: String? = null,
    val muted: Boolean? = null,
    val hash: String? = null,
    val path: String? = null,
    val accent: String? = null,
    val radius: String? = null,
    val background: String? = null,
    val label: String? = null,
    val layout: String? = null,
    val columns: JsonElement? = null,
    val height: Int? = null,
    val hours: Int? = null,
    val days: Int? = null,
    @SerialName("number_of_events") val numberOfEvents: Int? = null,
    @SerialName("number_of_hours") val numberOfHours: Int? = null,
    @SerialName("hours_to_show") val hoursToShow: String? = null,
    @SerialName("grid_area") val gridArea: String? = null,
    @SerialName("activity_entity") val activityEntity: String? = null,
    @SerialName("companion_entity") val companionEntity: String? = null,
    val battery: String? = null,
    @SerialName("home_sensor") val homeSensor: String? = null,
    @SerialName("temp_entity") val tempEntity: String? = null,
    @SerialName("sun_entity") val sunEntity: String? = null,
    @SerialName("graph_entity") val graphEntity: String? = null,
    val content: String? = null,
    val style: String? = null,
    @SerialName("default_tab") val defaultTab: Int? = null,
    @SerialName("emphasize_unlocked") val emphasizeUnlocked: Boolean? = null,
    val tap: ActionNode? = null,
    val hold: ActionNode? = null,
    val visibility: VisibilityNode? = null,
    val state: StateFormat? = null,
    val display: DisplayNode? = null,
    val cards: List<WidgetNode> = emptyList(),
    val chips: List<WidgetNode> = emptyList(),
    val tabs: List<TabNode> = emptyList(),
    val series: List<SeriesNode> = emptyList(),
    val calendars: List<CalendarSourceNode> = emptyList(),
    @SerialName("weather_entity") val weatherEntity: String? = null,
    @SerialName("show_navigation") val showNavigation: Boolean? = null,
    @SerialName("combine_similar") val combineSimilar: Boolean? = null,
    @SerialName("show_condition") val showCondition: Boolean? = null,
    @SerialName("show_temperature") val showTemperature: Boolean? = null,
    @SerialName("show_low_temperature") val showLowTemperature: Boolean? = null,
    val conditions: List<JsonObject> = emptyList(),
) {
    fun columnCount(): Int =
        (columns as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: 2
}

@Serializable
data class ActionNode(
    val type: String? = null,
    val service: String? = null,
    val hash: String? = null,
    @SerialName("entity_id") val entityId: JsonElement? = null,
    val data: JsonObject? = null,
)

@Serializable
data class VisibilityNode(
    val kind: String? = null,
    val entity: String? = null,
    val states: List<String> = emptyList(),
    val value: Double? = null,
)

@Serializable
data class StateFormat(
    val kind: String? = null,
    val entity: String? = null,
    val attribute: String? = null,
    val decimals: Int? = null,
    val suffix: String? = null,
    val scale: Double? = null,
)

@Serializable
data class DisplayNode(
    val kind: String? = null,
    @SerialName("temp_entity") val tempEntity: String? = null,
    @SerialName("hum_entity") val humEntity: String? = null,
    @SerialName("climate_entity") val climateEntity: String? = null,
)

@Serializable
data class SeriesNode(
    val entity: String? = null,
    val name: String? = null,
    val type: String? = null,
)

@Serializable
data class CalendarSourceNode(
    val entity: String? = null,
    val color: String? = null,
    val icon: String? = null,
)
