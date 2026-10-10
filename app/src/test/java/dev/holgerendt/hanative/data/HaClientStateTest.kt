package dev.holgerendt.hanative.data

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class HaClientStateTest {
    private val client = HaClient()

    @After fun tearDown() { client.release() }

    private fun change(id: String, value: String?) {
        val next = value?.let { """{"entity_id":"$id","state":"$it","attributes":{}}""" } ?: "null"
        client.handleMessage("""{"type":"event","event":{"event_type":"state_changed","data":{"entity_id":"$id","new_state":$next}}}""")
    }

    @Test fun removalWinsOverPendingAndPublishedUpdates() {
        change("light.room", "on")
        client.flushStateBatch()
        change("light.room", "off")
        change("light.room", null)
        client.flushStateBatch()
        assertNull(client.state("light.room"))
        change("light.room", "on")
        client.flushStateBatch()
        assertEquals("on", client.state("light.room")?.state)
    }

    @Test fun fullSnapshotSupersedesPendingEventsEvenWhenEmpty() {
        client.handleMessage("""{"type":"auth_ok"}""")
        change("light.room", "on")
        client.handleMessage("""{"id":4,"type":"result","success":true,"result":[]}""")
        client.flushStateBatch()
        assertTrue(client.states.value.isEmpty())
    }

    @Test fun unrelatedResultDoesNotClearEntityStates() {
        change("light.room", "on")
        client.flushStateBatch()
        client.handleMessage("""{"id":999,"type":"result","success":true,"result":[]}""")
        assertEquals("on", client.state("light.room")?.state)
    }

    @Test fun snapshotDoesNotReapplyOlderBufferedStates() {
        client.handleMessage("""{"type":"auth_ok"}""")
        change("light.room", "off")
        client.handleMessage("""{"id":4,"type":"result","success":true,"result":[{"entity_id":"light.room","state":"on","attributes":{}}]}""")
        client.flushStateBatch()
        assertEquals("on", client.state("light.room")?.state)
    }

    @Test fun concurrentIngestionAndFlushRetainEveryEntity() {
        val start = CountDownLatch(1)
        val writers = (0..3).map { writer ->
            thread {
                start.await()
                repeat(500) { change("sensor.writer_${writer}_$it", "$it") }
            }
        }
        val flusher = thread {
            start.await()
            repeat(1000) { client.flushStateBatch() }
        }
        start.countDown()
        writers.forEach { it.join() }
        flusher.join()
        client.flushStateBatch()
        assertEquals(2000, client.states.value.size)
        repeat(4) { writer ->
            repeat(500) { assertEquals("$it", client.state("sensor.writer_${writer}_$it")?.state) }
        }
    }

    @Test fun disconnectDiscardsPendingState() {
        change("light.room", "on")
        client.disconnect()
        client.flushStateBatch()
        assertNull(client.state("light.room"))
    }

    @Test fun overflowFailsConnectionAndRejectsOldSocketMessages() {
        val socket = FakeSocket()
        // Install a socket without starting a network connection or its queue consumer.
        HaClient::class.java.getDeclaredField("webSocket").apply { isAccessible = true }.set(client, socket)
        repeat(2048) { client.enqueueMessage(socket, "message $it") }
        assertFalse(socket.cancelled)
        client.enqueueMessage(socket, "overflow")
        assertTrue(socket.cancelled)
        val error = client.connection.value as ConnectionState.Error
        assertTrue(error.message.contains("backlog"))
        client.enqueueMessage(socket, "late message")
        assertEquals(error, client.connection.value)
    }

    @Test fun closeWhileConnectingLeavesDisconnectedAndNeedsReconnect() {
        val socket = FakeSocket()
        installSocket(socket, ConnectionState.Connecting)
        assertFalse(client.needsReconnect(nowMs = System.currentTimeMillis(), connectingTimeoutMs = 20_000L))
        client.markSocketClosed(socket, code = 1006, reason = "abnormal")
        assertEquals(ConnectionState.Disconnected, client.connection.value)
        assertTrue(client.needsReconnect())
    }

    @Test fun staleConnectingNeedsReconnectEvenWithLiveSocket() {
        val socket = FakeSocket()
        installSocket(socket, ConnectionState.Connecting)
        val started = HaClient::class.java.getDeclaredField("connectingSinceMs").apply {
            isAccessible = true
        }.getLong(client)
        assertFalse(client.needsReconnect(nowMs = started + 1_000L, connectingTimeoutMs = 20_000L))
        assertTrue(client.needsReconnect(nowMs = started + 21_000L, connectingTimeoutMs = 20_000L))
    }

    @Test fun closeWhileConnectedLeavesDisconnected() {
        val socket = FakeSocket()
        installSocket(socket, ConnectionState.Connected)
        client.markSocketClosed(socket)
        assertEquals(ConnectionState.Disconnected, client.connection.value)
        assertTrue(client.needsReconnect())
    }

    @Test fun socketFailureDrainsForecastWaitersWithoutThrowingTwice() {
        val socket = FakeSocket()
        installSocket(socket, ConnectionState.Connected)
        @Suppress("UNCHECKED_CAST")
        val map = HaClient::class.java.getDeclaredField("forecastSubscriptions").apply {
            isAccessible = true
        }.get(client) as java.util.concurrent.ConcurrentHashMap<Int, (List<*>) -> Unit>
        var calls = 0
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        map[7] = {
            // Mimic forecastViaSubscribe's one-shot resume; a second invoke must be a no-op.
            if (settled.compareAndSet(false, true)) calls++
            else throw IllegalStateException("Already resumed")
        }
        client.markSocketClosed(socket)
        // A second drain (stale close / failure race) must not crash the OkHttp thread.
        client.markSocketClosed(socket)
        assertEquals(1, calls)
        assertTrue(map.isEmpty())
    }

    private fun installSocket(socket: WebSocket, state: ConnectionState) {
        HaClient::class.java.getDeclaredField("webSocket").apply { isAccessible = true }.set(client, socket)
        HaClient::class.java.getDeclaredMethod("setConnection", ConnectionState::class.java).apply {
            isAccessible = true
            invoke(client, state)
        }
    }

    private class FakeSocket : WebSocket {
        var cancelled = false
        override fun request() = Request.Builder().url("http://127.0.0.1").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
}
