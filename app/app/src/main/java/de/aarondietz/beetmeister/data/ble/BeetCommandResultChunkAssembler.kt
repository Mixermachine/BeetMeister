package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import java.nio.charset.StandardCharsets
import java.util.Base64

internal class BeetCommandResultChunkAssembler(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private var activeChunkId: Long? = null
    private var expectedChunkCount: Int = 0
    private var nextChunkIndex: Int = 0
    private var lastUpdateMs: Long = 0L
    private val base64Builder = StringBuilder()

    val hasActiveChunks: Boolean
        get() = activeChunkId != null

    fun reset() {
        activeChunkId = null
        expectedChunkCount = 0
        nextChunkIndex = 0
        lastUpdateMs = 0L
        base64Builder.setLength(0)
    }

    fun consume(frame: BeetJsonCodec.CommandChunkFrame, nowMs: Long): String? {
        if (hasActiveChunks && (nowMs - lastUpdateMs) > timeoutMs) {
            reset()
            throw IllegalArgumentException("chunk timeout")
        }

        validateFrameBounds(frame)
        if (!hasActiveChunks) {
            if (frame.index != 0) {
                throw IllegalArgumentException("chunk sequence must start at index 0")
            }
            activeChunkId = frame.id
            expectedChunkCount = frame.count
            nextChunkIndex = 0
            base64Builder.setLength(0)
        }

        if (frame.id != activeChunkId) {
            reset()
            throw IllegalArgumentException("chunk id mismatch")
        }
        if (frame.count != expectedChunkCount) {
            reset()
            throw IllegalArgumentException("chunk count mismatch")
        }
        if (frame.index != nextChunkIndex) {
            reset()
            throw IllegalArgumentException("chunk index mismatch")
        }

        base64Builder.append(frame.base64Fragment)
        nextChunkIndex += 1
        lastUpdateMs = nowMs

        if (nextChunkIndex < expectedChunkCount) {
            return null
        }

        val decoded = try {
            Base64.getDecoder().decode(base64Builder.toString())
        } catch (_: IllegalArgumentException) {
            reset()
            throw IllegalArgumentException("invalid chunk base64")
        }
        val json = decoded.toString(StandardCharsets.UTF_8)
        reset()
        return json
    }

    private fun validateFrameBounds(frame: BeetJsonCodec.CommandChunkFrame) {
        if (frame.count <= 0) {
            throw IllegalArgumentException("chunk count must be positive")
        }
        if (frame.index < 0 || frame.index >= frame.count) {
            throw IllegalArgumentException("chunk index out of range")
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}
