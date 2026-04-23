package de.aarondietz.beetmeister

import de.aarondietz.beetmeister.beet.BeetJsonCodec
import de.aarondietz.beetmeister.beet.BeetStateMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeetJsonCodecTest {
    @Test
    fun parsesDeviceStateFrame() {
        val payload = """
            {
              "type":"device",
              "data":{
                "battery_state":"ACTIVE",
                "battery_mv":3340,
                "time_valid":true,
                "next_check_in_s":4812,
                "active_pumps":1,
                "wifi_connected":true,
                "mqtt_connected":false
              }
            }
        """.trimIndent()

        val message = BeetJsonCodec.parseStateMessage(payload) as BeetStateMessage.DeviceStateUpdate
        val state = message.data

        assertEquals("ACTIVE", state.batteryState)
        assertEquals(3340, state.batteryMillivolts)
        assertEquals(48, state.batteryPercentApprox)
        assertTrue(state.timeValid)
        assertEquals(4812, state.nextCheckInSeconds)
        assertEquals(1, state.activePumps)
        assertTrue(state.wifiConnected)
        assertFalse(state.mqttConnected)
    }

    @Test
    fun parsesCalibrationResult() {
        val payload = """
            {
              "cmd":"get_calibration",
              "status":"accepted",
              "reason":"none",
              "data":{
                "pair":3,
                "dry_mv":2450,
                "wet_mv":900,
                "source":"USER",
                "calibrated_at_unix_s":0
              }
            }
        """.trimIndent()

        val result = BeetJsonCodec.parseCommandResult(payload)

        assertEquals("get_calibration", result.command)
        assertEquals(3, result.calibration!!.pairIndex)
        assertEquals(2450, result.calibration!!.dryMillivolts)
        assertEquals(900, result.calibration!!.wetMillivolts)
    }

    @Test
    fun parsesPairStateWithEnabledAndSensorFlags() {
        val payload = """
            {
              "type":"pair",
              "data":{
                "pair":2,
                "state":"DISABLED",
                "moisture_pct":33,
                "sensor_mv":1320,
                "blocked":false,
                "block_reason":"NONE",
                "remaining_s":0,
                "source":"NONE",
                "enabled":false,
                "sensor_valid":false
              }
            }
        """.trimIndent()

        val message = BeetJsonCodec.parseStateMessage(payload) as BeetStateMessage.PairStateUpdate
        val state = message.data

        assertEquals(2, state.pairIndex)
        assertEquals("DISABLED", state.state)
        assertFalse(state.enabled)
        assertFalse(state.sensorValid)
    }

    @Test
    fun ignoresPairFrameWhenParsingDeviceState() {
        val payload = """
            {
              "type":"pair",
              "data":{
                "pair":2,
                "state":"IDLE",
                "moisture_pct":33,
                "sensor_mv":1320,
                "blocked":false,
                "block_reason":"NONE",
                "remaining_s":0,
                "source":"NONE",
                "enabled":true,
                "sensor_valid":true
              }
            }
        """.trimIndent()

        assertEquals(null, BeetJsonCodec.parseStateMessage(payload) as? BeetStateMessage.DeviceStateUpdate)
    }

    @Test
    fun ignoresDeviceFrameWhenParsingPairState() {
        val payload = """
            {
              "type":"device",
              "data":{
                "battery_state":"ACTIVE",
                "battery_mv":3340,
                "time_valid":true,
                "next_check_in_s":4812,
                "active_pumps":1,
                "wifi_connected":true,
                "mqtt_connected":false
              }
            }
        """.trimIndent()

        assertEquals(null, BeetJsonCodec.parseStateMessage(payload) as? BeetStateMessage.PairStateUpdate)
    }

    @Test
    fun parsesHistorySummaryResult() {
        val payload = """
            {
              "cmd":"get_history_summary",
              "status":"accepted",
              "reason":"none",
              "data":{
                "latest_seq_no":42,
                "event_count":4,
                "pair_totals_s":[0,10,20,30,0,0,0,0]
              }
            }
        """.trimIndent()

        val result = BeetJsonCodec.parseCommandResult(payload)

        assertEquals(42L, result.historySummary!!.latestSequenceNumber)
        assertEquals(4, result.historySummary!!.eventCount)
        assertEquals(listOf(0, 10, 20, 30, 0, 0, 0, 0), result.historySummary!!.pairTotalsSeconds)
    }

    @Test
    fun parsesCompactEventPayload() {
        val payload = """
            {
              "cmd":"get_event",
              "status":"accepted",
              "reason":"none",
              "data":{
                "seq":77,
                "pair":5,
                "src":2,
                "start":0,
                "end":0,
                "tv":0,
                "mb":40,
                "ma":60,
                "sb":1450,
                "sa":980,
                "req":180,
                "act":120,
                "stop":1,
                "block":0,
                "bs":3340,
                "be":3290
              }
            }
        """.trimIndent()

        val result = BeetJsonCodec.parseCommandResult(payload)

        assertEquals(77L, result.event!!.sequenceNumber)
        assertEquals(5, result.event!!.pairIndex)
        assertEquals(2, result.event!!.triggerSource)
        assertEquals(120, result.event!!.actualDurationSeconds)
        assertFalse(result.event!!.timeValid)
    }

    @Test
    fun buildsEnableDisableCommands() {
        assertEquals("""{"cmd":"disable_pair","data":{"pair":4}}""", BeetJsonCodec.disablePair(4))
        assertEquals("""{"cmd":"enable_pair","data":{"pair":4}}""", BeetJsonCodec.enablePair(4))
    }
}
