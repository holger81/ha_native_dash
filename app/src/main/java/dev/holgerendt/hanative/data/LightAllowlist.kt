package dev.holgerendt.hanative.data

object LightAllowlist {
    private val defaultExclude = Regex("screen|segment|led", RegexOption.IGNORE_CASE)

    fun isDefaultLight(entityId: String): Boolean =
        entityId.startsWith("light.") && !defaultExclude.containsMatchIn(entityId)

    fun isPickerCandidate(entityId: String): Boolean {
        val domain = entityId.substringBefore('.')
        return domain == "light" || domain == "switch"
    }

    fun defaultIds(states: Map<String, EntityState>): List<String> =
        states.keys.filter(::isDefaultLight).sorted()

    fun resolved(stored: List<String>?, states: Map<String, EntityState>): List<String> =
        stored ?: defaultIds(states)

    fun currentlyOn(stored: List<String>?, states: Map<String, EntityState>): List<String> =
        resolved(stored, states).filter { id -> states[id]?.state == "on" }
}
