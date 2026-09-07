package dev.holgerendt.hanative.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChangelogFile(
    val versions: List<ChangelogVersion> = emptyList(),
)

@Serializable
data class ChangelogVersion(
    val version: String,
    val date: String? = null,
    val notes: List<String> = emptyList(),
)

object Changelog {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context, limit: Int = 5): List<ChangelogVersion> =
        runCatching {
            context.assets.open("changelog.json").bufferedReader().use { it.readText() }
                .let { json.decodeFromString<ChangelogFile>(it).versions.take(limit.coerceAtLeast(1)) }
        }.getOrDefault(emptyList())
}
