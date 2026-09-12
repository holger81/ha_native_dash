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
