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
        if (wasPlaying == playing) return
        wasPlaying = playing
        hideJob?.cancel()
        if (playing) {
            _visible.value = true
        } else {
            hideJob = scope.launch {
                delay(60_000L)
                _visible.value = false
            }
        }
    }
}
