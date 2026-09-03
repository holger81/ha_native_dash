package dev.holgerendt.hanative.data

import android.content.Context
import dev.holgerendt.hanative.model.DashboardFile
import kotlinx.serialization.json.Json

object DashboardLoader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun load(context: Context): DashboardFile {
        val raw = context.assets.open("dashboard.json").bufferedReader().use { it.readText() }
        return json.decodeFromString(DashboardFile.serializer(), raw)
    }

    fun loadOrNull(context: Context): Pair<DashboardFile?, String?> =
        runCatching { load(context) to null }
            .getOrElse { null to (it.message ?: it::class.simpleName) }

    fun loadCodepoints(context: Context): Map<String, String> {
        val raw = context.assets.open("mdi_codepoints.json").bufferedReader().use { it.readText() }
        return json.decodeFromString<Map<String, String>>(raw)
    }
}
