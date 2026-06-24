package de.aarondietz.beetmeister.data.protocol

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.aarondietz.beetmeister.data.protocol.dto.CommandEnvelopeHeaderDto
import de.aarondietz.beetmeister.data.protocol.dto.CommandChunkEnvelopeDto
import de.aarondietz.beetmeister.data.protocol.dto.CommandRequestEnvelopeDto
import de.aarondietz.beetmeister.data.protocol.dto.StateEnvelopeHeaderDto
import de.aarondietz.beetmeister.model.command.BeetCalibrationCommandData
import de.aarondietz.beetmeister.model.command.BeetCommandAckData
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import de.aarondietz.beetmeister.model.command.BeetEmptyCommandData
import de.aarondietz.beetmeister.model.command.BeetEventRequestData
import de.aarondietz.beetmeister.model.command.BeetManualStartCommandData
import de.aarondietz.beetmeister.model.command.BeetPairCommandData
import de.aarondietz.beetmeister.model.command.BeetSetTimeCommandData
import de.aarondietz.beetmeister.model.command.BeetValveConfigCommandData
import de.aarondietz.beetmeister.model.command.BeetValvePreviewCommandData
import de.aarondietz.beetmeister.model.command.BeetWateringIntervalCommandData
import de.aarondietz.beetmeister.model.controller.BeetCalibration
import de.aarondietz.beetmeister.model.controller.BeetControllerInfo
import de.aarondietz.beetmeister.model.controller.BeetDeviceState
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.controller.BeetPairWiring
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.controller.BeetWateringInterval
import de.aarondietz.beetmeister.model.event.BeetHistorySummary
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetSystemHistorySummary
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.stream.BeetStateMessage
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetMaintenanceStatus
import java.nio.charset.StandardCharsets

object BeetJsonCodec {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private const val DATA_FIELD = "data"
    data class MaintenanceBeginUpdatePayload(
        val json: String,
        val compact: Boolean,
        val sizeBytes: Int,
    )

    private val stateEnvelopeHeaderAdapter: JsonAdapter<StateEnvelopeHeaderDto> =
        moshi.adapter(StateEnvelopeHeaderDto::class.java)
    private val commandEnvelopeHeaderAdapter: JsonAdapter<CommandEnvelopeHeaderDto> =
        moshi.adapter(CommandEnvelopeHeaderDto::class.java)
    private val commandChunkEnvelopeAdapter: JsonAdapter<CommandChunkEnvelopeDto> =
        moshi.adapter(CommandChunkEnvelopeDto::class.java)

    private val controllerInfoPayloadAdapter: JsonAdapter<BeetControllerInfo> =
        moshi.adapter(BeetControllerInfo::class.java)
    private val maintenanceInfoPayloadAdapter: JsonAdapter<BeetMaintenanceInfo> =
        moshi.adapter(BeetMaintenanceInfo::class.java)
    private val maintenanceStatusPayloadAdapter: JsonAdapter<BeetMaintenanceStatus> =
        moshi.adapter(BeetMaintenanceStatus::class.java)
    private val deviceStatePayloadAdapter: JsonAdapter<BeetDeviceState> =
        moshi.adapter(BeetDeviceState::class.java)
    private val pairStatePayloadAdapter: JsonAdapter<BeetPairState> =
        moshi.adapter(BeetPairState::class.java)
    private val systemEventPayloadAdapter: JsonAdapter<BeetSystemEvent> =
        moshi.adapter(BeetSystemEvent::class.java)
    private val commandAckPayloadAdapter: JsonAdapter<BeetCommandAckData> =
        moshi.adapter(BeetCommandAckData::class.java)
    private val calibrationPayloadAdapter: JsonAdapter<BeetCalibration> =
        moshi.adapter(BeetCalibration::class.java)
    private val pairWiringPayloadAdapter: JsonAdapter<BeetPairWiring> =
        moshi.adapter(BeetPairWiring::class.java)
    private val valveConfigPayloadAdapter: JsonAdapter<BeetValveConfig> =
        moshi.adapter(BeetValveConfig::class.java)
    private val wateringIntervalPayloadAdapter: JsonAdapter<BeetWateringInterval> =
        moshi.adapter(BeetWateringInterval::class.java)
    private val historySummaryPayloadAdapter: JsonAdapter<BeetHistorySummary> =
        moshi.adapter(BeetHistorySummary::class.java)
    private val systemHistorySummaryPayloadAdapter: JsonAdapter<BeetSystemHistorySummary> =
        moshi.adapter(BeetSystemHistorySummary::class.java)
    private val wateringEventPayloadAdapter: JsonAdapter<BeetWateringEvent> =
        moshi.adapter(BeetWateringEvent::class.java)

    private val manualStartEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetManualStartCommandData::class.java)
    private val pairRequestEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetPairCommandData::class.java)
    private val calibrationRequestEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetCalibrationCommandData::class.java)
    private val eventRequestEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetEventRequestData::class.java)
    private val setTimeRequestEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetSetTimeCommandData::class.java)
    private val valveConfigRequestEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetValveConfigCommandData::class.java)
    private val valvePreviewRequestEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetValvePreviewCommandData::class.java)
    private val wateringIntervalRequestEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetWateringIntervalCommandData::class.java)
    private val emptyRequestEnvelopeAdapter = commandRequestEnvelopeAdapter(BeetEmptyCommandData::class.java)

    data class CommandChunkFrame(
        val id: Long,
        val index: Int,
        val count: Int,
        val base64Fragment: String,
    )

    fun parseControllerInfo(payload: String): BeetControllerInfo {
        val header = stateEnvelopeHeaderAdapter.fromJson(payload) ?: error("Invalid controller info envelope.")
        check(header.type == "controller_info") { "Unexpected controller info type ${header.type}." }
        val dto = controllerInfoPayloadAdapter.fromJson(extractRequiredObjectField(payload, DATA_FIELD))
            ?: error("Invalid controller info payload.")
        return BeetControllerInfo(
            deviceId = dto.deviceId,
            protocolVersion = dto.protocolVersion,
            firmwareVersion = dto.firmwareVersion,
            pairCount = dto.pairCount,
        )
    }

    fun parseStateMessage(payload: String): BeetStateMessage? {
        val header = stateEnvelopeHeaderAdapter.fromJson(payload) ?: return null
        val dataJson = extractObjectField(payload, DATA_FIELD) ?: return null
        return when (header.type) {
            "device" -> {
                val dto = deviceStatePayloadAdapter.fromJson(dataJson) ?: return null
                BeetStateMessage.DeviceStateUpdate(dto)
            }

            "pair" -> {
                val dto = pairStatePayloadAdapter.fromJson(dataJson) ?: return null
                BeetStateMessage.PairStateUpdate(dto)
            }

            "system_event" -> {
                val dto = systemEventPayloadAdapter.fromJson(dataJson) ?: return null
                BeetStateMessage.SystemEventUpdate(dto)
            }

            else -> null
        }
    }

    fun parseCommandResult(payload: String): BeetCommandResult {
        val header = commandEnvelopeHeaderAdapter.fromJson(payload) ?: error("Invalid command result envelope.")
        val dataJson = extractObjectField(payload, DATA_FIELD) ?: "{}"
        val calibration = if (header.cmd == "get_calibration" && header.status == "accepted") {
            calibrationPayloadAdapter.fromJson(dataJson) ?: error("Invalid calibration payload.")
        }
        else {
            null
        }
        val historySummary = if (header.cmd == "get_history_summary" && header.status == "accepted") {
            historySummaryPayloadAdapter.fromJson(dataJson) ?: error("Invalid history summary payload.")
        } else {
            null
        }
        val systemHistorySummary = if (header.cmd == "get_system_history_summary" && header.status == "accepted") {
            systemHistorySummaryPayloadAdapter.fromJson(dataJson) ?: error("Invalid system history summary payload.")
        } else {
            null
        }
        val event = if (header.cmd == "get_event" && header.status == "accepted") {
            wateringEventPayloadAdapter.fromJson(dataJson) ?: error("Invalid event payload.")
        } else {
            null
        }
        val systemEvent = if (header.cmd == "get_system_event" && header.status == "accepted") {
            systemEventPayloadAdapter.fromJson(dataJson) ?: error("Invalid system event payload.")
        } else {
            null
        }
        val valveConfig = if (
            (header.cmd == "get_valve_config" || header.cmd == "store_valve_config") &&
            header.status == "accepted"
        ) {
            valveConfigPayloadAdapter.fromJson(dataJson) ?: error("Invalid valve config payload.")
        } else {
            null
        }
        val wateringInterval = if (
            (header.cmd == "get_watering_interval" || header.cmd == "store_watering_interval") &&
            header.status == "accepted"
        ) {
            wateringIntervalPayloadAdapter.fromJson(dataJson) ?: error("Invalid watering interval payload.")
        } else {
            null
        }
        val pairWiring = if (header.cmd == "get_pair_wiring" && header.status == "accepted") {
            pairWiringPayloadAdapter.fromJson(dataJson) ?: error("Invalid pair wiring payload.")
        } else {
            null
        }
        val ack = commandAckPayloadAdapter.fromJson(dataJson)
        return BeetCommandResult(
            command = header.cmd,
            pairIndex = calibration?.pairIndex ?: pairWiring?.pairIndex ?: event?.pairIndex ?: ack?.pairIndex,
            status = header.status,
            reason = header.reason,
            acceptedDurationSeconds = ack?.durationSeconds,
            calibration = calibration,
            historySummary = historySummary,
            event = event,
            systemHistorySummary = systemHistorySummary,
            systemEvent = systemEvent,
            valveConfig = valveConfig,
            wateringInterval = wateringInterval,
            pairWiring = pairWiring,
        )
    }

    fun parseMaintenanceInfo(payload: String): BeetMaintenanceInfo {
        val header = stateEnvelopeHeaderAdapter.fromJson(payload) ?: error("Invalid maintenance info envelope.")
        check(header.type == "maintenance_info") { "Unexpected maintenance info type ${header.type}." }
        return maintenanceInfoPayloadAdapter.fromJson(extractRequiredObjectField(payload, DATA_FIELD))
            ?: error("Invalid maintenance info payload.")
    }

    fun parseMaintenanceStatus(payload: String): BeetMaintenanceStatus {
        val header = stateEnvelopeHeaderAdapter.fromJson(payload) ?: error("Invalid maintenance status envelope.")
        check(header.type == "maintenance_status") { "Unexpected maintenance status type ${header.type}." }
        return maintenanceStatusPayloadAdapter.fromJson(extractRequiredObjectField(payload, DATA_FIELD))
            ?: error("Invalid maintenance status payload.")
    }

    // WARNING: The maintenance protocol wire contract is effectively frozen.
    // Keep command names, required fields, compact aliases, and field semantics
    // backward compatible across shipped controllers. Do not change this JSON
    // shape without an explicit protocol decision coordinated with firmware.
    fun maintenanceQueryStatus(): String = """{"cmd":"query_status"}"""

    fun maintenanceAbortUpdate(): String = """{"cmd":"abort_update"}"""

    fun maintenanceFinishUpdate(): String = """{"cmd":"finish_update"}"""

    fun maintenanceBeginUpdate(
        firmware: BeetFirmwarePackageSummary,
        maxPayloadBytes: Int,
    ): MaintenanceBeginUpdatePayload {
        // WARNING: The verbose and compact begin_update payloads are both part
        // of the fixed maintenance protocol surface. Future edits must preserve
        // backward compatibility for shipped controllers, including compact
        // aliases such as d/fv/bl/sz/sh/pi/hr/ai/ik.
        val hardwareRevs = firmware.metadata.compatibleHardwareRevs.joinToString(separator = ",") { "\"$it\"" }
        val imageKind = firmware.metadata.imageKind
        val verbosePayload =
            """{"cmd":"begin_update","data":{"firmware_version":"${escapeJson(firmware.metadata.firmwareVersion)}","build_label":"${escapeJson(firmware.metadata.buildLabel)}","image_size":${firmware.imageSize},"image_sha256":"${firmware.sha256Hex}","product_id":"${escapeJson(firmware.metadata.productId)}","hardware_revs":[${hardwareRevs}],"runtime_protocol_version":${firmware.metadata.runtimeProtocolVersion},"asset_id":"${escapeJson(firmware.assetId)}","image_kind":"${escapeJson(imageKind)}"}}""".trimIndent()
        val verboseSize = verbosePayload.toByteArray(StandardCharsets.UTF_8).size
        if (verboseSize <= maxPayloadBytes) {
            return MaintenanceBeginUpdatePayload(
                json = verbosePayload,
                compact = false,
                sizeBytes = verboseSize,
            )
        }
        val compactPayload =
            """{"cmd":"begin_update","d":{"fv":"${escapeJson(firmware.metadata.firmwareVersion)}","bl":"${escapeJson(firmware.metadata.buildLabel)}","sz":${firmware.imageSize},"sh":"${firmware.sha256Hex}","pi":"${escapeJson(firmware.metadata.productId)}","hr":[${hardwareRevs}],"ai":"${escapeJson(firmware.assetId)}","ik":"${escapeJson(imageKind)}"}}""".trimIndent()
        val compactSize = compactPayload.toByteArray(StandardCharsets.UTF_8).size
        require(compactSize <= maxPayloadBytes) {
            "Maintenance begin_update payload exceeds $maxPayloadBytes bytes for negotiated MTU."
        }
        return MaintenanceBeginUpdatePayload(
            json = compactPayload,
            compact = true,
            sizeBytes = compactSize,
        )
    }

    fun parseCommandChunk(payload: String): CommandChunkFrame? {
        if (!payload.contains("\"type\"") || !payload.contains("cmd_chunk")) {
            return null
        }
        val envelope = commandChunkEnvelopeAdapter.fromJson(payload) ?: return null
        if (envelope.type != "cmd_chunk") {
            return null
        }
        return CommandChunkFrame(
            id = envelope.id,
            index = envelope.index,
            count = envelope.count,
            base64Fragment = envelope.base64Fragment,
        )
    }

    private fun escapeJson(value: String): String =
        buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

    fun manualStart(pairIndex: Int, durationSeconds: Int?): String =
        manualStartEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "manual_start",
                data = BeetManualStartCommandData(
                    pairIndex = pairIndex,
                    durationSeconds = durationSeconds,
                ),
            ),
        )

    fun manualStop(pairIndex: Int): String =
        pairRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "manual_stop",
                data = BeetPairCommandData(pairIndex = pairIndex),
            ),
        )

    fun resetBlock(pairIndex: Int): String =
        pairRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "reset_block",
                data = BeetPairCommandData(pairIndex = pairIndex),
            ),
        )

    fun moistureTestStart(pairIndex: Int): String =
        pairRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "moisture_test_start",
                data = BeetPairCommandData(pairIndex = pairIndex),
            ),
        )

    fun storeCalibration(pairIndex: Int, dryMillivolts: Int, wetMillivolts: Int): String =
        calibrationRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "store_calibration",
                data = BeetCalibrationCommandData(
                    pairIndex = pairIndex,
                    dryMillivolts = dryMillivolts,
                    wetMillivolts = wetMillivolts,
                ),
            ),
        )

    fun getCalibration(pairIndex: Int): String =
        pairRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "get_calibration",
                data = BeetPairCommandData(pairIndex = pairIndex),
            ),
        )

    fun getPairWiring(pairIndex: Int): String =
        pairRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "get_pair_wiring",
                data = BeetPairCommandData(pairIndex = pairIndex),
            ),
        )

    fun getHistorySummary(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "get_history_summary",
                data = BeetEmptyCommandData(),
            ),
        )

    fun getEvent(sequenceNumber: Long): String =
        eventRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "get_event",
                data = BeetEventRequestData(sequenceNumber = sequenceNumber),
            ),
        )

    fun getSystemHistorySummary(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "get_system_history_summary",
                data = BeetEmptyCommandData(),
            ),
        )

    fun getSystemEvent(sequenceNumber: Long): String =
        eventRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "get_system_event",
                data = BeetEventRequestData(sequenceNumber = sequenceNumber),
            ),
        )

    fun setTime(unixSeconds: Long): String =
        setTimeRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "set_time",
                data = BeetSetTimeCommandData(unixSeconds = unixSeconds),
            ),
        )

    fun clearBleBonds(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "clear_ble_bonds",
                data = BeetEmptyCommandData(),
            ),
        )

    fun rebootController(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "reboot_controller",
                data = BeetEmptyCommandData(),
            ),
        )

    fun factoryResetController(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "factory_reset",
                data = BeetEmptyCommandData(),
            ),
        )

    fun runScheduler(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "run_scheduler",
                data = BeetEmptyCommandData(),
            ),
        )

    fun getValveConfig(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "get_valve_config",
                data = BeetEmptyCommandData(),
            ),
        )

    fun storeValveConfig(config: BeetValveConfig): String =
        valveConfigRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "store_valve_config",
                data = BeetValveConfigCommandData(
                    valveEnabled = config.valveEnabled,
                    servoMinPulseMicros = config.servoMinPulseMicros,
                    servoMaxPulseMicros = config.servoMaxPulseMicros,
                    openPulseMicros = config.openPulseMicros,
                    shutPulseMicros = config.shutPulseMicros,
                    moveDurationMillis = config.moveDurationMillis,
                    settleDelayMillis = config.settleDelayMillis,
                    openHoldMillis = config.openHoldMillis,
                ),
            ),
        )

    fun previewValvePosition(pulseMicros: Int): String =
        valvePreviewRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "preview_valve_position",
                data = BeetValvePreviewCommandData(pulseMicros = pulseMicros),
            ),
        )

    fun openValve(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "open_valve",
                data = BeetEmptyCommandData(),
            ),
        )

    fun closeValve(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "close_valve",
                data = BeetEmptyCommandData(),
            ),
        )

    fun getWateringInterval(): String =
        emptyRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "get_watering_interval",
                data = BeetEmptyCommandData(),
            ),
        )

    fun storeWateringInterval(seconds: Int): String =
        wateringIntervalRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "store_watering_interval",
                data = BeetWateringIntervalCommandData(seconds = seconds),
            ),
        )

    fun wateringEventToJson(event: BeetWateringEvent): String = wateringEventPayloadAdapter.toJson(event)

    fun wateringEventFromJson(json: String): BeetWateringEvent? = wateringEventPayloadAdapter.fromJson(json)

    fun systemEventToJson(event: BeetSystemEvent): String = systemEventPayloadAdapter.toJson(event)

    fun systemEventFromJson(json: String): BeetSystemEvent? = systemEventPayloadAdapter.fromJson(json)

    fun disablePair(pairIndex: Int): String =
        pairRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "disable_pair",
                data = BeetPairCommandData(pairIndex = pairIndex),
            ),
        )

    fun enablePair(pairIndex: Int): String =
        pairRequestEnvelopeAdapter.toJson(
            CommandRequestEnvelopeDto(
                cmd = "enable_pair",
                data = BeetPairCommandData(pairIndex = pairIndex),
            ),
        )

    private fun <T> commandRequestEnvelopeAdapter(payloadClass: Class<T>): JsonAdapter<CommandRequestEnvelopeDto<T>> {
        val type = Types.newParameterizedType(CommandRequestEnvelopeDto::class.java, payloadClass)
        @Suppress("UNCHECKED_CAST")
        return moshi.adapter<CommandRequestEnvelopeDto<T>>(type)
    }

    private fun extractRequiredObjectField(payload: String, fieldName: String): String =
        extractObjectField(payload, fieldName) ?: error("Missing $fieldName object.")

    private fun extractObjectField(payload: String, fieldName: String): String? {
        val keyToken = "\"$fieldName\""
        val keyIndex = payload.indexOf(keyToken)
        if (keyIndex < 0) {
            return null
        }
        val colonIndex = payload.indexOf(':', startIndex = keyIndex + keyToken.length)
        if (colonIndex < 0) {
            return null
        }
        var objectStart = colonIndex + 1
        while (objectStart < payload.length && payload[objectStart].isWhitespace()) {
            objectStart++
        }
        if (objectStart >= payload.length || payload[objectStart] != '{') {
            return null
        }

        var depth = 0
        var inString = false
        var escaping = false
        for (index in objectStart until payload.length) {
            val char = payload[index]
            when {
                escaping -> escaping = false
                char == '\\' && inString -> escaping = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) {
                        return payload.substring(objectStart, index + 1)
                    }
                }
            }
        }
        return null
    }
}
