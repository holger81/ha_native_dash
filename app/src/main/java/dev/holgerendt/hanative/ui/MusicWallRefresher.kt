package dev.holgerendt.hanative.ui

import dev.holgerendt.hanative.data.MusicAssistantPlayer
import dev.holgerendt.hanative.data.MusicAssistantQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Applies only the newest refresh, preserving edits made while network requests are in flight. */
internal class MusicWallRefresher(
    private val state: MutableStateFlow<MusicWallState>,
    private val loadPlayers: suspend () -> List<MusicAssistantPlayer>,
    private val refreshPlayers: suspend (List<MusicAssistantPlayer>) -> List<MusicAssistantPlayer>,
    private val loadQueue: suspend (String, List<MusicAssistantPlayer>) -> MusicAssistantQueue?,
    private val savedSelection: () -> String?,
    private val saveSelection: (String) -> Unit,
    private val playerState: (String) -> String?,
) {
    private val revision = AtomicLong()

    fun invalidate() { revision.incrementAndGet() }

    suspend fun refresh(forcePlayers: Boolean) {
        val request = revision.incrementAndGet()
        val initial = state.value
        fun isCurrent() = revision.get() == request && state.value.selectedEntityId == initial.selectedEntityId
        try {
            val players = if (forcePlayers || initial.players.isEmpty()) {
                loadPlayers()
            } else {
                try { refreshPlayers(initial.players) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { initial.players }
            }
            if (!isCurrent()) return
            val preferred = initial.selectedEntityId ?: savedSelection()
            val selected = preferred?.takeIf { id -> players.any { it.entityId == id } }
                ?: players.firstOrNull { playerState(it.entityId) == "playing" }?.entityId
                ?: players.firstOrNull { playerState(it.entityId) == "paused" }?.entityId
                ?: players.firstOrNull()?.entityId
            val queue = if (selected == null) null else {
                try { loadQueue(selected, players) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return
            state.update { latest ->
                if (revision.get() != request || latest.selectedEntityId != initial.selectedEntityId) latest
                else latest.copy(
                    loading = false,
                    players = players,
                    selectedEntityId = selected,
                    queue = queue,
                    error = if (players.isEmpty())
                        "No media players found. Add the Music Assistant integration in Home Assistant for the full wall player."
                    else null,
                )
            }
            if (revision.get() == request && selected != null && state.value.selectedEntityId == selected) {
                if (selected != savedSelection()) saveSelection(selected)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { latest ->
                if (!isCurrent()) latest
                else latest.copy(loading = false, error = error.message ?: "Music player failed to load")
            }
        }
    }
}
