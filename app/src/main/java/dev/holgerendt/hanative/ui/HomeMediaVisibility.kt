package dev.holgerendt.hanative.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owned by the view model so camera-layout changes cannot restart the idle grace period. */
internal class HomeMediaVisibility(private val scope: CoroutineScope) {
    private val _visible = MutableStateFlow(true)
    val visible = _visible.asStateFlow()
    private var wasPlaying: Boolean? = null
    private var hideJob: Job? = null

    fun updatePlaying(playing: Boolean) {
        if (playing) {
            wasPlaying = true
            hideJob?.cancel()
            hideJob = null
            _visible.value = true
            return
        }
        // Already counting down or already hidden — ignore repeated paused refreshes.
        if (wasPlaying == false && (hideJob?.isActive == true || !_visible.value)) return
        wasPlaying = false
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(HIDE_AFTER_MS)
            _visible.value = false
        }
    }

    companion object {
        /** Auto-hide paused/stopped home music card (1–2 minute window). */
        const val HIDE_AFTER_MS = 90_000L
    }
}
