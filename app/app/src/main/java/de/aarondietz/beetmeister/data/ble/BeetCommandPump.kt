package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.data.repository.BeetRepositoryCallbacks
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets

/**
 * Narrow write surface the command pump needs from the physical link.
 * Implemented by [BeetGattLink]; the pump never touches BluetoothGatt directly.
 */
internal interface BeetGattWriter {
    /** Throws the session's "no connected controller / control point unavailable" errors when not writable. */
    fun ensureWritable()

    /** Sets WRITE_TYPE_DEFAULT and starts the control-point write; false when the stack rejected it. */
    fun writeCommand(payload: String): Boolean
}

/**
 * Command serialization extracted from BeetGattSessionCoordinator (Phase 2b of the
 * coordinator split plan): owns the command mutex, the pending-command deferred and
 * its name-based stale-result correlation, the chunk reassembly pipeline, the
 * per-command timeout, and the sync-pause bridge that lets user commands preempt
 * background event sync.
 *
 * Pure relocation: no protocol, timing, or ordering changes. Result side effects
 * (state updates) and expected-action bookkeeping stay with the coordinator, which
 * applies them between [decodeCommandPayload] and [completePendingResult] exactly
 * where the original inline code did.
 */
internal class BeetCommandPump(
    private val host: BeetRepositoryCallbacks,
    private val writer: BeetGattWriter,
) {
    private val strings get() = host.strings
    private val commandMutex = Mutex()
    private val chunkAssembler = BeetCommandResultChunkAssembler()

    /** Set while a user command wants background sync paused; read by the sync engines. */
    @Volatile
    var syncPauseRequested = false

    /* Command name of the in-flight sendCommand; used to drop stale results
       that arrive after a sendCommand timeout (they must not complete the
       next command's deferred with the wrong ack). */
    @Volatile
    private var pendingCommandName: String? = null

    fun resetChunkAssembler() {
        chunkAssembler.reset()
    }

    suspend fun <T> withSyncPausedForCommand(block: suspend () -> T): T {
        syncPauseRequested = true
        host.updateState { state ->
            if (state.eventSync.active) {
                state.copy(eventSync = state.eventSync.copy(phase = de.aarondietz.beetmeister.model.stream.BeetEventSyncPhase.PausedForCommand))
            } else {
                state
            }
        }
        return try {
            block()
        } finally {
            syncPauseRequested = false
        }
    }

    suspend fun awaitSyncResumeIfNeeded() {
        while (syncPauseRequested && host.state.value.connection.phase == BeetConnectionPhase.Connected) {
            delay(SYNC_PAUSE_POLL_MS)
        }
    }

    suspend fun sendSyncCommand(payload: String): BeetCommandResult {
        awaitSyncResumeIfNeeded()
        return sendCommand(payload)
    }

    suspend fun sendCommand(payload: String): BeetCommandResult {
        return commandMutex.withLock {
            writer.ensureWritable()
            val deferred = CompletableDeferred<BeetCommandResult>()
            host.session.pendingCommand = deferred
            pendingCommandName = BeetJsonCodec.commandName(payload)

            BeetLog.d(TAG) { "sendCommand payload=$payload" }
            val writeStarted = writer.writeCommand(payload)
            if (!writeStarted) {
                host.session.pendingCommand = null
                error(strings.get(R.string.runtime_ble_send_failed))
            }

            try {
                withTimeout(COMMAND_TIMEOUT_MS) {
                    val result = deferred.await()
                    BeetLog.d(TAG) { "sendCommand result command=${result.command} status=${result.status} reason=${result.reason}" }
                    result
                }
            } catch (timeout: TimeoutCancellationException) {
                BeetLog.w(TAG) { "sendCommand timed out waiting for result payload=$payload" }
                chunkAssembler.reset()
                throw timeout
            } finally {
                host.session.pendingCommand = null
                pendingCommandName = null
            }
        }
    }

    /**
     * Chunk-reassembly + JSON parse half of the original command-result pipeline.
     * Returns null when the payload was an incomplete chunk frame, a failed chunk
     * reassembly, or unparseable (the original returned from the handler in all
     * three cases).
     */
    fun decodeCommandPayload(payload: ByteArray): BeetCommandResult? {
        val payloadString = payload.toString(StandardCharsets.UTF_8)
        val chunkFrame = try {
            BeetJsonCodec.parseCommandChunk(payloadString)
        } catch (error: Exception) {
            BeetLog.e(TAG, "Command chunk parse failed payload=$payloadString", error)
            chunkAssembler.reset()
            return null
        }
        val decodedPayload = if (chunkFrame != null) {
            try {
                BeetLog.d(TAG) { "Received command chunk id=${chunkFrame.id} index=${chunkFrame.index}/${chunkFrame.count}" }
                chunkAssembler.consume(chunkFrame, System.currentTimeMillis())?.also {
                    BeetLog.d(TAG) { "Completed chunk reassembly id=${chunkFrame.id} totalLen=${it.length}" }
                }
            } catch (error: Exception) {
                BeetLog.e(
                    TAG,
                    "Command chunk reassembly failed id=${chunkFrame.id} index=${chunkFrame.index} count=${chunkFrame.count}",
                    error,
                )
                chunkAssembler.reset()
                return null
            } ?: return null
        } else {
            if (chunkAssembler.hasActiveChunks) {
                BeetLog.w(TAG, "Command chunk reassembly reset due to non-chunk payload while chunked response is active")
                chunkAssembler.reset()
            }
            payloadString
        }
        return try {
            BeetJsonCodec.parseCommandResult(decodedPayload)
        } catch (error: Exception) {
            BeetLog.e(TAG, "Command payload parse failed", error)
            null
        }
    }

    /**
     * Stale-name gate + completion of the pending deferred; the tail of the
     * original pipeline, run after the coordinator applied result side effects.
     */
    fun completePendingResult(result: BeetCommandResult) {
        val expected = pendingCommandName
        if (expected != null && result.command != null && result.command != expected) {
            BeetLog.w(TAG) { "dropping stale result cmd=${result.command} while awaiting $expected" }
            return
        }
        host.session.pendingCommand?.complete(result)
    }

    companion object {
        const val TAG = "BeetGattSession"
        const val COMMAND_TIMEOUT_MS = 7_000L
        const val SYNC_PAUSE_POLL_MS = 50L
    }
}
