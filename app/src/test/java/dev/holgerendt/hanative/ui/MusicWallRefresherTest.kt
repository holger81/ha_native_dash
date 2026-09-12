package dev.holgerendt.hanative.ui

import dev.holgerendt.hanative.data.MusicAssistantPlayer
import dev.holgerendt.hanative.data.MusicAssistantQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicWallRefresherTest {
    private val players = listOf(MusicAssistantPlayer("media_player.a", "A"), MusicAssistantPlayer("media_player.b", "B"))
    private val state = MutableStateFlow(MusicWallState(players = players, selectedEntityId = "media_player.a"))
    private var saved = "media_player.a"

    private fun refresher(queue: suspend (String) -> MusicAssistantQueue?) = MusicWallRefresher(
        state, { players }, { it }, { id, _ -> queue(id) }, { saved }, { saved = it }, { "idle" },
    )

    @Test fun preservesTabAndSearchEditsDuringNetworkRequest() = runBlocking {
        val response = CompletableDeferred<MusicAssistantQueue>()
        val refresher = refresher { response.await() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(false) }
        state.value = state.value.copy(tab = "discover", discovery = MusicDiscoveryState(searchQuery = "new search"))
        response.complete(MusicAssistantQueue(queueId = "a"))
        job.join()
        assertEquals("discover", state.value.tab)
        assertEquals("new search", state.value.discovery.searchQuery)
        assertEquals("a", state.value.queue?.queueId)
    }

    @Test fun oldSpeakerResponseCannotUndoNewSelectionOrQueue() = runBlocking {
        val oldResponse = CompletableDeferred<MusicAssistantQueue>()
        val refresher = refresher { id -> if (id == "media_player.a") oldResponse.await() else MusicAssistantQueue(queueId = "b") }
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(false) }
        refresher.invalidate()
        saved = "media_player.b"
        state.value = state.value.copy(selectedEntityId = saved)
        refresher.refresh(false)
        oldResponse.complete(MusicAssistantQueue(queueId = "a"))
        oldJob.join()
        assertEquals("media_player.b", state.value.selectedEntityId)
        assertEquals("b", state.value.queue?.queueId)
        assertEquals("media_player.b", saved)
    }

    @Test fun overlappingRefreshesKeepNewestResponse() = runBlocking {
        val first = CompletableDeferred<MusicAssistantQueue>()
        var calls = 0
        val refresher = refresher { if (++calls == 1) first.await() else MusicAssistantQueue(queueId = "new") }
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(false) }
        refresher.refresh(false)
        first.complete(MusicAssistantQueue(queueId = "old"))
        oldJob.join()
        assertEquals("new", state.value.queue?.queueId)
    }

    @Test fun cancellingRefreshDoesNotPublishFallbackQueue() = runBlocking {
        val response = CompletableDeferred<MusicAssistantQueue>()
        val before = state.value
        val refresher = refresher { response.await() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(false) }
        job.cancel()
        job.join()
        assertEquals(before, state.value)
    }

    @Test fun invalidationDiscardsResponseAfterPopupCloses() = runBlocking {
        val response = CompletableDeferred<MusicAssistantQueue>()
        val before = state.value
        val refresher = refresher { response.await() }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { refresher.refresh(false) }
        refresher.invalidate()
        response.complete(MusicAssistantQueue(queueId = "closed"))
        job.join()
        assertEquals(before, state.value)
    }
}
