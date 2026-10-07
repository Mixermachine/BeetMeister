package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.model.command.BeetStreamAck
import de.aarondietz.beetmeister.model.stream.BeetStreamEnd
import java.util.concurrent.atomic.AtomicLong

/** Which record ring a runtime stream_events burst targets. */
internal enum class BeetStreamKind(val wireName: String) {
    WATERING("watering"),
    SYSTEM("system"),
}

/**
 * Tier 2 frame demultiplexer for state-stream burst frames.
 *
 * Stream frames (event / system_event / stream_end during an armed burst) are
 * correlated here; command results stay on the command-result pipeline and are
 * never completed by router input (hard rule 1: a stream frame can never
 * complete a pending command, and a stale stream_end can never complete the
 * current session).
 *
 * Called from the GATT callback context; all state is thread-safe.
 */
internal class BeetResponseRouter {
    class Session(val kind: BeetStreamKind) {
        @Volatile
        var ack: BeetStreamAck? = null

        val delivered = AtomicLong(0L)
        @Volatile
        var lastActivityMs: Long = System.currentTimeMillis()

        val end = kotlinx.coroutines.CompletableDeferred<BeetStreamEnd>()

        fun noteActivity() {
            lastActivityMs = System.currentTimeMillis()
        }
    }

    private val sessions = java.util.concurrent.ConcurrentHashMap<BeetStreamKind, Session>()

    val activeSessionCount: Int
        get() = sessions.size

    /** Registers a new session for [kind]; any previous session for the kind is replaced. */
    fun begin(kind: BeetStreamKind): Session {
        val session = Session(kind)
        sessions[kind] = session
        return session
    }

    fun session(kind: BeetStreamKind): Session? = sessions[kind]

    /**
     * Routes one delivered event frame for [kind] to its active session.
     * Returns true when the frame was consumed by a stream session (the live
     * ingestion path still runs regardless; this only drives progress/idle).
     */
    fun onEventFrame(kind: BeetStreamKind): Boolean {
        val session = sessions[kind] ?: return false
        session.delivered.incrementAndGet()
        session.noteActivity()
        return true
    }

    /**
     * Routes a stream_end frame. Ignored when no session is armed for the kind
     * or when the frame belongs to a replaced (stale) stream id.
     */
    fun onStreamEnd(end: BeetStreamEnd): Boolean {
        val kind = if (end.kind == BeetStreamKind.SYSTEM.wireName) BeetStreamKind.SYSTEM else BeetStreamKind.WATERING
        val session = sessions[kind] ?: return false
        val ack = session.ack
        if (ack != null && end.streamId != ack.streamId) {
            return false
        }
        session.noteActivity()
        sessions.remove(kind)
        session.end.complete(end)
        return true
    }

    /** Drops the session for [kind] without completing it (abort path). */
    fun abort(kind: BeetStreamKind) {
        sessions.remove(kind)
    }
}

