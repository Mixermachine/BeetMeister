package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.model.command.BeetStreamAck
import de.aarondietz.beetmeister.model.stream.BeetStreamEnd
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

internal sealed class BeetStreamRun {
    /** Command rejected or ack unparseable: caller must use the legacy per-sequence path. */
    data object Unsupported : BeetStreamRun()

    /** stream_end received; resume cursor for this kind is [nextCursor]. */
    data class Completed(val ack: BeetStreamAck, val end: BeetStreamEnd, val nextCursor: Long) : BeetStreamRun()

    /** stream_cancel sent by us, or controller-side cancellation; partial progress consumed. */
    data class Cancelled(val delivered: Long, val nextCursor: Long) : BeetStreamRun()

    /** Connection dropped mid-stream; partial progress is safely cached. */
    data class Disconnected(val delivered: Long, val nextCursor: Long) : BeetStreamRun()
}

/**
 * Burst-stream orchestration for the runtime protocol v19 stream_events
 * command (Tier 2). One command write arms the controller pump; records then
 * arrive as state-stream notifications and are correlated by [router] until a
 * stream_end frame completes the wait.
 */
internal class BeetEventSyncEngine(
    private val link: BeetRuntimeCommandLink,
    val router: BeetResponseRouter = BeetResponseRouter(),
) {
    /**
     * Streams one kind's backlog from [fromSeq]. [isCancelled] is polled at the
     * same cadence as the legacy runner polls its pause flag; when it flips the
     * burst is cancelled controller-side (hard rule 3: cancelable, resumable).
     */
    suspend fun streamEvents(
        kind: BeetStreamKind,
        fromSeq: Long,
        maxEvents: Long,
        isCancelled: () -> Boolean,
        isConnected: () -> Boolean,
        onAck: (BeetStreamAck) -> Unit = {},
    ): BeetStreamRun {
        val session = router.begin(kind)
        val ackResult = runCatching {
            link.sendCommand(BeetJsonCodec.streamEvents(kind.wireName, fromSeq, maxEvents))
        }.getOrNull()

        val ack = ackResult?.streamAck
        if (ackResult == null || ackResult.status != "accepted" || ack == null || ack.kind != kind.wireName) {
            router.abort(kind)
            BeetLog.w(TAG) { "stream ${kind.wireName} unsupported: ack=${ackResult?.status ?: "timeout"}" }
            if (ackResult == null) {
                /* The command may still have been armed controller-side while its
                   ack got lost; stop it so a stale pump does not flood the next
                   session. Best-effort: the channel may still be congested. */
                runCatching { link.sendCommand(BeetJsonCodec.streamCancel()) }
            }
            return BeetStreamRun.Unsupported
        }
        session.ack = ack
        session.noteActivity()
        onAck(ack)
        val resumeBase = ack.fromSeq

        val startedMs = System.currentTimeMillis()
        while (!session.end.isCompleted) {
            if (!isConnected()) {
                router.abort(kind)
                return BeetStreamRun.Disconnected(session.delivered.get(), resumeBase + session.delivered.get())
            }
            if (isCancelled()) {
                return cancelSession(kind, session, resumeBase, "pause requested")
            }
            val idleMs = System.currentTimeMillis() - session.lastActivityMs
            val totalMs = System.currentTimeMillis() - startedMs
            if (idleMs > IDLE_TIMEOUT_MS || totalMs > HARD_CAP_MS) {
                return cancelSession(
                    kind,
                    session,
                    resumeBase,
                    if (idleMs > IDLE_TIMEOUT_MS) "idle timeout" else "hard cap",
                )
            }
            withTimeoutOrNull(POLL_MS) { session.end.await() }
        }

        val end = session.end.getCompleted()
            ?: run {
                router.abort(kind)
                return BeetStreamRun.Unsupported
            }
        val delivered = maxOf(end.delivered, session.delivered.get())
        if (end.status != "complete") {
            // cancelled by the controller side (e.g. OTA started mid-stream):
            // partial progress is ingested; caller decides on resume/legacy.
            return BeetStreamRun.Cancelled(delivered, maxOf(end.lastSeq + 1, resumeBase + delivered))
        }
        val nextCursor = if (end.lastSeq >= resumeBase) end.lastSeq + 1 else resumeBase + delivered
        return BeetStreamRun.Completed(ack, end, nextCursor)
    }

    private suspend fun cancelSession(
        kind: BeetStreamKind,
        session: BeetResponseRouter.Session,
        resumeBase: Long,
        reason: String,
    ): BeetStreamRun {
        runCatching { link.sendCommand(BeetJsonCodec.streamCancel()) }
        router.abort(kind)
        BeetLog.w(TAG) { "stream ${kind.wireName} cancelled: $reason delivered=${session.delivered.get()}" }
        return BeetStreamRun.Cancelled(session.delivered.get(), resumeBase + session.delivered.get())
    }

    private companion object {
        const val TAG = "BeetEventSyncEngine"
        const val POLL_MS = 50L
        const val IDLE_TIMEOUT_MS = 10_000L
        const val HARD_CAP_MS = 10 * 60_000L
    }
}
