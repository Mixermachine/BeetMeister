package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.model.command.BeetCommandResult
import de.aarondietz.beetmeister.model.command.BeetStreamAck
import de.aarondietz.beetmeister.model.stream.BeetStreamEnd
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 hard-rule coverage for the response router and event sync engine.
 * Uses a scripted command link; no Android or BLE dependencies.
 */
class BeetEventStreamSyncTest {

    private class FakeLink : BeetRuntimeCommandLink {
        val payloads = mutableListOf<String>()
        var ack: BeetStreamAck? = null

        override suspend fun sendCommand(payload: String): BeetCommandResult {
            payloads += payload
            val streamAck = ack
            return BeetCommandResult(
                command = if (payload.contains("stream_events")) "stream_events" else "other",
                pairIndex = null,
                status = if (streamAck != null && payload.contains("stream_events")) "accepted" else "rejected",
                reason = "none",
                streamAck = streamAck,
            )
        }

        override suspend fun <T> withSyncPausedForCommand(block: suspend () -> T): T = block()
        override fun applyUserCommandSideEffects(result: BeetCommandResult) = Unit
    }

    private fun ack(id: Long, kind: String, from: Long, latest: Long, total: Long) =
        BeetStreamAck(streamId = id, kind = kind, fromSeq = from, latestSeq = latest, total = total)

    private fun end(id: Long, kind: String, status: String, delivered: Long, lastSeq: Long, gaps: Long = 0) =
        BeetStreamEnd(streamId = id, kind = kind, status = status, delivered = delivered, lastSeq = lastSeq, gaps = gaps)

    @Test
    fun routerRoutesEventsToArmedSessionOnly() {
        val router = BeetResponseRouter()
        assertFalse(router.onEventFrame(BeetStreamKind.WATERING))

        val session = router.begin(BeetStreamKind.WATERING)
        assertTrue(router.onEventFrame(BeetStreamKind.WATERING))
        assertFalse(router.onEventFrame(BeetStreamKind.SYSTEM))
        assertEquals(1L, session.delivered.get())
    }

    @Test
    fun routerIgnoresStaleStreamEndId() {
        val router = BeetResponseRouter()
        val session = router.begin(BeetStreamKind.WATERING)
        session.ack = ack(id = 5, kind = "watering", from = 1, latest = 9, total = 9)

        // Hard rule 1: a stale terminal frame must not complete the current session.
        assertFalse(router.onStreamEnd(end(id = 4, kind = "watering", status = "complete", delivered = 2, lastSeq = 3)))
        assertFalse(session.end.isCompleted)

        assertTrue(router.onStreamEnd(end(id = 5, kind = "watering", status = "complete", delivered = 9, lastSeq = 9)))
        assertTrue(session.end.isCompleted)
    }

    @Test
    fun completedStreamAdvancesCursor() = runBlocking {
        val link = FakeLink()
        val router = BeetResponseRouter()
        val engine = BeetEventSyncEngine(link, router)
        link.ack = ack(id = 1, kind = "watering", from = 1, latest = 3, total = 3)

        val run = engine.streamEvents(
            kind = BeetStreamKind.WATERING,
            fromSeq = 1,
            maxEvents = 100,
            isCancelled = { false },
            isConnected = { true },
            onAck = { a ->
                // Frames land while the engine is still in its ack handling path.
                repeat(3) { router.onEventFrame(BeetStreamKind.WATERING) }
                router.onStreamEnd(end(id = a.streamId, kind = "watering", status = "complete", delivered = 3, lastSeq = 3))
            },
        )

        assertTrue("expected Completed, got $run", run is BeetStreamRun.Completed)
        run as BeetStreamRun.Completed
        assertEquals(4L, run.nextCursor)
        assertEquals(3L, run.end.delivered)
    }

    @Test
    fun unsupportedWhenControllerRejects() = runBlocking {
        val link = FakeLink()
        val engine = BeetEventSyncEngine(link, BeetResponseRouter())
        link.ack = null // fake link returns rejected for stream_events

        val run = engine.streamEvents(
            kind = BeetStreamKind.SYSTEM,
            fromSeq = 1,
            maxEvents = 10,
            isCancelled = { false },
            isConnected = { true },
        )
        assertEquals(BeetStreamRun.Unsupported, run)
    }

    @Test
    fun pauseCancelsAndReportsResumeCursor() = runBlocking {
        val link = FakeLink()
        val router = BeetResponseRouter()
        val engine = BeetEventSyncEngine(link, router)
        link.ack = ack(id = 7, kind = "watering", from = 10, latest = 50, total = 41)

        val run = engine.streamEvents(
            kind = BeetStreamKind.WATERING,
            fromSeq = 10,
            maxEvents = 100,
            isCancelled = { true }, // pause requested immediately
            isConnected = { true },
            onAck = { a ->
                router.onEventFrame(BeetStreamKind.WATERING)
                router.onEventFrame(BeetStreamKind.WATERING)
            },
        )

        assertTrue("expected Cancelled, got $run", run is BeetStreamRun.Cancelled)
        run as BeetStreamRun.Cancelled
        assertEquals(2L, run.delivered)
        // Hard rule 3: cancelable and resumable - cursor is just after delivered prefix.
        assertEquals(12L, run.nextCursor)
        // And we actually told the controller to stop.
        assertTrue(link.payloads.any { it.contains("stream_cancel") })
    }

    @Test
    fun controllerSideCancellationSurfacesAsCancelledRun() = runBlocking {
        val link = FakeLink()
        val router = BeetResponseRouter()
        val engine = BeetEventSyncEngine(link, router)
        link.ack = ack(id = 9, kind = "system", from = 1, latest = 20, total = 20)

        val run = engine.streamEvents(
            kind = BeetStreamKind.SYSTEM,
            fromSeq = 1,
            maxEvents = 100,
            isCancelled = { false },
            isConnected = { true },
            onAck = { a ->
                router.onEventFrame(BeetStreamKind.SYSTEM)
                // OTA started controller-side: pump emits cancelled terminal frame.
                router.onStreamEnd(end(id = a.streamId, kind = "system", status = "cancelled", delivered = 1, lastSeq = 1))
            },
        )

        assertTrue("expected Cancelled, got $run", run is BeetStreamRun.Cancelled)
        assertEquals(2L, (run as BeetStreamRun.Cancelled).nextCursor)
    }
}
