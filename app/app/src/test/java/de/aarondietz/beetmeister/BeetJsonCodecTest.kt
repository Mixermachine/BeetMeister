package de.aarondietz.beetmeister

import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.stream.BeetStateMessage
import de.aarondietz.beetmeister.model.update.BeetFirmwareMetadata
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetFirmwareSource
import java.nio.charset.StandardCharsets
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
                "boot_id":11,
                "next_check_in_s":4812,
                "active_pumps":1,
                "wifi_connected":true,
                "mqtt_connected":false,
                "uptime_s":123,
                "valve_enabled":true,
                "valve_state":"OPEN"
              }
            }
        """.trimIndent()

        val message = BeetJsonCodec.parseStateMessage(payload) as BeetStateMessage.DeviceStateUpdate
        val state = message.data

        assertEquals("ACTIVE", state.batteryState)
        assertEquals(3340, state.batteryMillivolts)
        assertEquals(48, state.batteryPercentApprox)
        assertTrue(state.timeValid)
        assertEquals(11L, state.bootId)
        assertEquals(4812, state.nextCheckInSeconds)
        assertEquals(1, state.activePumps)
        assertTrue(state.wifiConnected)
        assertFalse(state.mqttConnected)
        assertEquals(123L, state.uptimeSeconds)
        assertTrue(state.valveEnabled)
        assertEquals("OPEN", state.valveState)
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
                "boot_id":11,
                "src":2,
                "start":0,
                "end":0,
                "mb":40,
                "ma":60,
                "sb":1450,
                "sa":980,
                "req":180,
                "act":120,
                "stop":1,
                "block":0,
                "bs":3340,
                "be":3290,
                "su":12,
                "eu":132
              }
            }
        """.trimIndent()

        val result = BeetJsonCodec.parseCommandResult(payload)

        assertEquals(77L, result.event!!.sequenceNumber)
        assertEquals(5, result.event!!.pairIndex)
        assertEquals(11L, result.event!!.bootId)
        assertEquals(2, result.event!!.triggerSource)
        assertEquals(120, result.event!!.actualDurationSeconds)
        assertEquals(132L, result.event!!.endedUptimeSeconds)
    }

    @Test
    fun parsesSystemEventFrameAndResult() {
        val frame = """
            {
              "type":"system_event",
              "data":{
                "seq":9,
                "event_type":"BLE_CONNECT",
                "reason":0,
                "boot_id":11,
                "uptime_s":123,
                "unix_s":0,
                "battery_mv":3340,
                "peer_addr":"AA:BB:CC:DD:EE:FF",
                "peer_addr_type":1,
                "known_peer":true,
                "detail":0
              }
            }
        """.trimIndent()

        val message = BeetJsonCodec.parseStateMessage(frame) as BeetStateMessage.SystemEventUpdate

        assertEquals(9L, message.data.sequenceNumber)
        assertEquals("BLE_CONNECT", message.data.eventType)
        assertEquals(11L, message.data.bootId)
        assertEquals("AA:BB:CC:DD:EE:FF", message.data.peerAddress)

        val result = BeetJsonCodec.parseCommandResult(
            """
                {
                  "cmd":"get_system_history_summary",
                  "status":"accepted",
                  "reason":"none",
                  "data":{"latest_seq_no":9,"event_count":3}
                }
            """.trimIndent(),
        )

        assertEquals(9L, result.systemHistorySummary!!.latestSequenceNumber)
        assertEquals(3, result.systemHistorySummary!!.eventCount)
    }

    @Test
    fun parsesControllerSleepEventPayload() {
        val payload = """
            {
              "cmd":"get_event",
              "status":"accepted",
              "reason":"none",
              "data":{
                "seq":78,
                "pair":0,
                "src":0,
                "start":0,
                "end":0,
                "mb":0,
                "ma":0,
                "sb":0,
                "sa":0,
                "req":0,
                "act":0,
                "stop":6,
                "block":0,
                "bs":3270,
                "be":3270
              }
            }
        """.trimIndent()

        val result = BeetJsonCodec.parseCommandResult(payload)

        assertEquals(78L, result.event!!.sequenceNumber)
        assertEquals(0, result.event!!.pairIndex)
        assertEquals(6, result.event!!.stopReason)
        assertEquals(3270, result.event!!.batteryStartMillivolts)
        assertTrue(result.event!!.isControllerSleepEvent)
    }

    @Test
    fun buildsEnableDisableCommands() {
        assertEquals("""{"cmd":"disable_pair","data":{"pair":4}}""", BeetJsonCodec.disablePair(4))
        assertEquals("""{"cmd":"enable_pair","data":{"pair":4}}""", BeetJsonCodec.enablePair(4))
    }

    @Test
    fun buildsMoistureTestCommand() {
        assertEquals("""{"cmd":"moisture_test_start","data":{"pair":4}}""", BeetJsonCodec.moistureTestStart(4))
    }

    @Test
    fun buildsSetTimeCommand() {
        assertEquals("""{"cmd":"set_time","data":{"unix_s":1714412345}}""", BeetJsonCodec.setTime(1714412345))
    }

    @Test
    fun buildsClearBleBondsCommand() {
        assertEquals("""{"cmd":"clear_ble_bonds","data":{}}""", BeetJsonCodec.clearBleBonds())
    }

    @Test
    fun buildsRebootAndFactoryResetCommands() {
        assertEquals("""{"cmd":"reboot_controller","data":{}}""", BeetJsonCodec.rebootController())
        assertEquals("""{"cmd":"factory_reset","data":{}}""", BeetJsonCodec.factoryResetController())
    }

    @Test
    fun parsesValveConfigResult() {
        val payload = """
            {
              "cmd":"get_valve_config",
              "status":"accepted",
              "reason":"none",
              "data":{
                "valve_enabled":true,
                "servo_min_pulse_us":700,
                "servo_max_pulse_us":2300,
                "open_pulse_us":930,
                "shut_pulse_us":2010,
                "move_duration_ms":800,
                "settle_delay_ms":250,
                "open_hold_ms":1200
              }
            }
        """.trimIndent()

        val result = BeetJsonCodec.parseCommandResult(payload)

        assertTrue(result.valveConfig!!.valveEnabled)
        assertEquals(700, result.valveConfig!!.servoMinPulseMicros)
        assertEquals(2300, result.valveConfig!!.servoMaxPulseMicros)
        assertEquals(930, result.valveConfig!!.openPulseMicros)
        assertEquals(2010, result.valveConfig!!.shutPulseMicros)
        assertEquals(800, result.valveConfig!!.moveDurationMillis)
        assertEquals(250, result.valveConfig!!.settleDelayMillis)
        assertEquals(1200, result.valveConfig!!.openHoldMillis)
    }

    @Test
    fun buildsValveCommands() {
        assertEquals("""{"cmd":"get_valve_config","data":{}}""", BeetJsonCodec.getValveConfig())
        assertEquals("""{"cmd":"open_valve","data":{}}""", BeetJsonCodec.openValve())
        assertEquals("""{"cmd":"close_valve","data":{}}""", BeetJsonCodec.closeValve())
        assertEquals("""{"cmd":"preview_valve_position","data":{"pulse_us":1600}}""", BeetJsonCodec.previewValvePosition(1600))
        assertEquals(
            """{"cmd":"store_valve_config","data":{"valve_enabled":true,"servo_min_pulse_us":600,"servo_max_pulse_us":2400,"open_pulse_us":920,"shut_pulse_us":2080,"move_duration_ms":700,"settle_delay_ms":200,"open_hold_ms":1500}}""",
            BeetJsonCodec.storeValveConfig(
                BeetValveConfig(
                    valveEnabled = true,
                    servoMinPulseMicros = 600,
                    servoMaxPulseMicros = 2400,
                    openPulseMicros = 920,
                    shutPulseMicros = 2080,
                    moveDurationMillis = 700,
                    settleDelayMillis = 200,
                    openHoldMillis = 1500,
                ),
            ),
        )
    }

    @Test
    fun parsesAndBuildsWateringIntervalCommands() {
        val payload = """
            {
              "cmd":"get_watering_interval",
              "status":"accepted",
              "reason":"none",
              "data":{
                "watering_interval_s":21600
              }
            }
        """.trimIndent()

        val result = BeetJsonCodec.parseCommandResult(payload)

        assertEquals(21600, result.wateringInterval!!.seconds)
        assertEquals("""{"cmd":"get_watering_interval","data":{}}""", BeetJsonCodec.getWateringInterval())
        assertEquals(
            """{"cmd":"store_watering_interval","data":{"watering_interval_s":21600}}""",
            BeetJsonCodec.storeWateringInterval(21600),
        )
    }

    @Test
    fun parsesMaintenanceInfoFrame() {
        val payload = """
            {
              "type":"maintenance_info",
              "data":{
                "product_id":"beetmeister",
                "hardware_rev":"rev_a",
                "firmware_version":"0.2.0",
                "build_label":"v0.2.0",
                "maintenance_protocol_version":1,
                "runtime_protocol_version":9,
                "update_capable":true,
                "image_kind":"bundled"
              }
            }
        """.trimIndent()

        val info = BeetJsonCodec.parseMaintenanceInfo(payload)

        assertEquals("beetmeister", info.productId)
        assertEquals("rev_a", info.hardwareRev)
        assertEquals("0.2.0", info.firmwareVersion)
        assertEquals("v0.2.0", info.buildLabel)
        assertEquals(1, info.maintenanceProtocolVersion)
        assertEquals(9, info.runtimeProtocolVersion)
        assertTrue(info.updateCapable)
        assertEquals("bundled", info.imageKind)
    }

    @Test
    fun parsesMaintenanceStatusFrame() {
        val payload = """
            {
              "type":"maintenance_status",
              "data":{
                "state":"transferring",
                "session_id":42,
                "next_offset":1024,
                "bytes_received":1024,
                "total_bytes":4096
              }
            }
        """.trimIndent()

        val status = BeetJsonCodec.parseMaintenanceStatus(payload)

        assertEquals("transferring", status.state)
        assertEquals(42, status.sessionId)
        assertEquals(1024, status.nextOffset)
        assertEquals(1024, status.bytesReceived)
        assertEquals(4096, status.totalBytes)
    }

    @Test
    fun buildsMaintenanceBeginUpdateCommand() {
        val payload = BeetJsonCodec.maintenanceBeginUpdate(
            firmware = BeetFirmwarePackageSummary(
                source = BeetFirmwareSource.Bundled,
                sourceLabel = "Bundled",
                assetId = "bundled-dev",
                metadata = BeetFirmwareMetadata(
                    productId = "beetmeister",
                    hardwareRev = "rev_a",
                    firmwareVersion = "0.2.0",
                    buildLabel = "v0.2.0",
                    maintenanceProtocolVersion = 1,
                    runtimeProtocolVersion = 9,
                    imageKind = "bundled",
                    compatibleHardwareRevs = listOf("rev_a", "rev_b"),
                ),
                imageSize = 4096,
                sha256Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                isDowngrade = false,
                runtimeProtocolWarning = false,
            ),
            maxPayloadBytes = 512,
        )

        assertFalse(payload.compact)
        assertTrue(payload.json.contains("\"cmd\":\"begin_update\""))
        assertTrue(payload.json.contains("\"firmware_version\":\"0.2.0\""))
        assertTrue(payload.json.contains("\"build_label\":\"v0.2.0\""))
        assertTrue(payload.json.contains("\"image_size\":4096"))
        assertTrue(payload.json.contains("\"hardware_revs\":[\"rev_a\",\"rev_b\"]"))
        assertTrue(payload.json.contains("\"runtime_protocol_version\":9"))
        assertTrue(payload.json.contains("\"asset_id\":\"bundled-dev\""))
        assertTrue(payload.json.contains("\"image_kind\":\"bundled\""))
        assertTrue(payload.sizeBytes <= 512)
    }

    @Test
    fun buildsMaintenanceBeginUpdateCommandForCustomImage() {
        val payload = BeetJsonCodec.maintenanceBeginUpdate(
            firmware = BeetFirmwarePackageSummary(
                source = BeetFirmwareSource.Custom,
                sourceLabel = "my-build.bin",
                assetId = "custom",
                metadata = BeetFirmwareMetadata(
                    productId = "beetmeister",
                    hardwareRev = "rev_a",
                    firmwareVersion = "0.2.0",
                    buildLabel = "custom-abc1234",
                    maintenanceProtocolVersion = 1,
                    runtimeProtocolVersion = 10,
                    imageKind = "custom",
                    compatibleHardwareRevs = listOf("rev_a"),
                ),
                imageSize = 4096,
                sha256Hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                isDowngrade = false,
                runtimeProtocolWarning = true,
            ),
            maxPayloadBytes = 220,
        )

        assertTrue(payload.compact)
        assertTrue(payload.json.contains("\"ai\":\"custom\""))
        assertTrue(payload.json.contains("\"rp\":10"))
        assertTrue(payload.json.contains("\"ik\":\"custom\""))
        assertTrue(payload.sizeBytes <= 220)
    }
}
