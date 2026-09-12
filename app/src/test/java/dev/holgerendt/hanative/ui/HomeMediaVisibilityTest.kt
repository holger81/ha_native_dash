package dev.holgerendt.hanative.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeMediaVisibilityTest {
    @Test fun hidesAfterOneMinuteWithoutPlayback() = runTest {
        val visibility = HomeMediaVisibility(backgroundScope)
        visibility.updatePlaying(false)
        advanceTimeBy(59_999)
        assertTrue(visibility.visible.value)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(visibility.visible.value)
    }

    @Test fun refreshesAndCameraRemountsDoNotRestartOrRevealIdleCard() = runTest {
        val visibility = HomeMediaVisibility(backgroundScope)
        visibility.updatePlaying(false)
        advanceTimeBy(30_000)
        visibility.updatePlaying(false)
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(visibility.visible.value)
        visibility.updatePlaying(false)
        assertFalse(visibility.visible.value)
    }

    @Test fun playbackCancelsPendingHideAndNextPauseGetsFullMinute() = runTest {
        val visibility = HomeMediaVisibility(backgroundScope)
        visibility.updatePlaying(false)
        advanceTimeBy(30_000)
        visibility.updatePlaying(true)
        advanceTimeBy(60_000)
        assertTrue(visibility.visible.value)
        visibility.updatePlaying(false)
        advanceTimeBy(59_999)
        assertTrue(visibility.visible.value)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(visibility.visible.value)
        visibility.updatePlaying(true)
        assertTrue(visibility.visible.value)
    }
}
