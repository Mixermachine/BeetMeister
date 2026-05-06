package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BeetCommandResultChunkAssemblerTest {
    @Test
    fun singlePayloadCommandResultStillParses() {
        val payload = """
            {
              "cmd":"get_history_summary",
              "status":"accepted",
              "reason":"none",
              "data":{"latest_seq_no":42,"event_count":4,"pair_totals_s":[0,10,20,30,0,0,0,0]}
            }
        """.trimIndent()

        val result = BeetJsonCodec.parseCommandResult(payload)

        assertEquals("get_history_summary", result.command)
        assertNotNull(result.historySummary)
        assertEquals(42L, result.historySummary!!.latestSequenceNumber)
    }

    @Test
    fun reassemblesTwoChunkPayloadAndParsesResult() {
        val payload = systemEventPayload(detail = 123456)
        val assembler = BeetCommandResultChunkAssembler()
        val chunks = chunkFramesFor(payload, id = 17L, chunkCount = 2)

        val first = assembler.consume(chunks[0], nowMs = 1_000L)
        val second = assembler.consume(chunks[1], nowMs = 1_100L)

        assertNull(first)
        assertEquals(payload, second)

        val parsed = BeetJsonCodec.parseCommandResult(second!!)
        assertEquals("get_system_event", parsed.command)
        assertEquals(9L, parsed.systemEvent!!.sequenceNumber)
        assertEquals("BLE_CONNECT", parsed.systemEvent!!.eventType)
    }

    @Test
    fun reassemblesThreeChunkPayloadAndParsesResult() {
        val payload = systemEventPayload(detail = 987654321)
        val assembler = BeetCommandResultChunkAssembler()
        val chunks = chunkFramesFor(payload, id = 18L, chunkCount = 3)

        assertNull(assembler.consume(chunks[0], nowMs = 2_000L))
        assertNull(assembler.consume(chunks[1], nowMs = 2_100L))
        val reassembled = assembler.consume(chunks[2], nowMs = 2_200L)

        assertEquals(payload, reassembled)
        assertEquals(987654321L, BeetJsonCodec.parseCommandResult(reassembled!!).systemEvent!!.detail)
    }

    @Test
    fun failsOnWrongChunkId() {
        val payload = systemEventPayload(detail = 1)
        val chunks = chunkFramesFor(payload, id = 99L, chunkCount = 2)
        val wrongId = chunks[1].copy(id = 100L)
        val assembler = BeetCommandResultChunkAssembler()

        assembler.consume(chunks[0], nowMs = 10L)
        expectIllegalArgument { assembler.consume(wrongId, nowMs = 20L) }
        assertTrue(!assembler.hasActiveChunks)
    }

    @Test
    fun failsOnMissingOrOutOfOrderOrDuplicateChunk() {
        val payload = systemEventPayload(detail = 2)
        val chunks = chunkFramesFor(payload, id = 40L, chunkCount = 3)

        run {
            val assembler = BeetCommandResultChunkAssembler()
            assembler.consume(chunks[0], nowMs = 1L)
            expectIllegalArgument { assembler.consume(chunks[2], nowMs = 2L) }
        }

        run {
            val assembler = BeetCommandResultChunkAssembler()
            assembler.consume(chunks[0], nowMs = 1L)
            expectIllegalArgument { assembler.consume(chunks[0], nowMs = 2L) }
        }

        run {
            val assembler = BeetCommandResultChunkAssembler()
            expectIllegalArgument { assembler.consume(chunks[1], nowMs = 1L) }
        }
    }

    @Test
    fun failsOnTruncatedBase64() {
        val payload = systemEventPayload(detail = 3)
        val chunks = chunkFramesFor(payload, id = 200L, chunkCount = 2).toMutableList()
        val corrupted = chunks[1].base64Fragment.dropLast(1)
        chunks[1] = chunks[1].copy(base64Fragment = corrupted)
        val assembler = BeetCommandResultChunkAssembler()

        assembler.consume(chunks[0], nowMs = 100L)
        expectIllegalArgument { assembler.consume(chunks[1], nowMs = 200L) }
    }

    @Test
    fun resetClearsReassemblyState() {
        val payload = systemEventPayload(detail = 4)
        val chunks = chunkFramesFor(payload, id = 300L, chunkCount = 2)
        val assembler = BeetCommandResultChunkAssembler()

        assembler.consume(chunks[0], nowMs = 10L)
        assembler.reset()
        expectIllegalArgument { assembler.consume(chunks[1], nowMs = 20L) }

        val restarted = chunkFramesFor(payload, id = 301L, chunkCount = 2)
        assertNull(assembler.consume(restarted[0], nowMs = 30L))
        assertEquals(payload, assembler.consume(restarted[1], nowMs = 40L))
    }

    @Test
    fun timeoutClearsReassemblyState() {
        val payload = systemEventPayload(detail = 5)
        val chunks = chunkFramesFor(payload, id = 500L, chunkCount = 2)
        val assembler = BeetCommandResultChunkAssembler(timeoutMs = 50)

        assembler.consume(chunks[0], nowMs = 1_000L)
        expectIllegalArgument { assembler.consume(chunks[1], nowMs = 1_100L) }
        assertTrue(!assembler.hasActiveChunks)
    }

    @Test
    fun codecParsesChunkEnvelope() {
        val frameJson = """{"type":"cmd_chunk","id":7,"i":0,"n":2,"b64":"QUJD"}"""

        val frame = BeetJsonCodec.parseCommandChunk(frameJson)

        assertNotNull(frame)
        assertEquals(7L, frame!!.id)
        assertEquals(0, frame.index)
        assertEquals(2, frame.count)
        assertEquals("QUJD", frame.base64Fragment)
    }

    private fun systemEventPayload(detail: Long): String =
        """
            {
              "cmd":"get_system_event",
              "status":"accepted",
              "reason":"none",
              "data":{
                "seq":9,
                "event_type":"BLE_CONNECT",
                "reason":22,
                "boot_id":9,
                "uptime_s":123,
                "unix_s":0,
                "battery_mv":3340,
                "peer_addr":"AA:BB:CC:DD:EE:FF",
                "peer_addr_type":1,
                "known_peer":true,
                "detail":$detail
              }
            }
        """.trimIndent()

    private fun chunkFramesFor(payload: String, id: Long, chunkCount: Int): List<BeetJsonCodec.CommandChunkFrame> {
        val base64 = Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val partSize = (base64.length + chunkCount - 1) / chunkCount
        return (0 until chunkCount).map { index ->
            val start = index * partSize
            val end = minOf(base64.length, start + partSize)
            val fragment = if (start < end) base64.substring(start, end) else ""
            val frameJson = """{"type":"cmd_chunk","id":$id,"i":$index,"n":$chunkCount,"b64":"$fragment"}"""
            BeetJsonCodec.parseCommandChunk(frameJson)!!
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
