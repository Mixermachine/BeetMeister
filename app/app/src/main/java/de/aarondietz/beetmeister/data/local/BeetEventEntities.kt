package de.aarondietz.beetmeister.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent

@Entity(
    tableName = "watering_events",
    primaryKeys = ["deviceId", "seqNo"],
    indices = [Index(value = ["deviceId", "endedAt"])],
)
internal data class BeetWateringEventEntity(
    val deviceId: String,
    val seqNo: Long,
    val pairIndex: Int,
    val bootId: Long,
    val triggerSource: Int,
    val startedAt: Long,
    val endedAt: Long,
    val moistureBefore: Int,
    val moistureAfter: Int,
    val sensorBeforeMv: Int,
    val sensorAfterMv: Int,
    val requestedDuration: Int,
    val actualDuration: Int,
    val stopReason: Int,
    val blockReason: Int,
    val batteryStartMv: Int,
    val batteryEndMv: Int,
    val startedUptime: Long,
    val endedUptime: Long,
)

internal fun BeetWateringEvent.toEntity(deviceId: String): BeetWateringEventEntity =
    BeetWateringEventEntity(
        deviceId = deviceId,
        seqNo = sequenceNumber,
        pairIndex = pairIndex,
        bootId = bootId,
        triggerSource = triggerSource,
        startedAt = startedAtUnixSeconds,
        endedAt = endedAtUnixSeconds,
        moistureBefore = moistureBeforePercent,
        moistureAfter = moistureAfterPercent,
        sensorBeforeMv = sensorBeforeMillivolts,
        sensorAfterMv = sensorAfterMillivolts,
        requestedDuration = requestedDurationSeconds,
        actualDuration = actualDurationSeconds,
        stopReason = stopReason,
        blockReason = blockReason,
        batteryStartMv = batteryStartMillivolts,
        batteryEndMv = batteryEndMillivolts,
        startedUptime = startedUptimeSeconds,
        endedUptime = endedUptimeSeconds,
    )

internal fun BeetWateringEventEntity.toModel(): BeetWateringEvent =
    BeetWateringEvent(
        sequenceNumber = seqNo,
        pairIndex = pairIndex,
        bootId = bootId,
        triggerSource = triggerSource,
        startedAtUnixSeconds = startedAt,
        endedAtUnixSeconds = endedAt,
        moistureBeforePercent = moistureBefore,
        moistureAfterPercent = moistureAfter,
        sensorBeforeMillivolts = sensorBeforeMv,
        sensorAfterMillivolts = sensorAfterMv,
        requestedDurationSeconds = requestedDuration,
        actualDurationSeconds = actualDuration,
        stopReason = stopReason,
        blockReason = blockReason,
        batteryStartMillivolts = batteryStartMv,
        batteryEndMillivolts = batteryEndMv,
        startedUptimeSeconds = startedUptime,
        endedUptimeSeconds = endedUptime,
    )

@Entity(
    tableName = "system_events",
    primaryKeys = ["deviceId", "seqNo"],
    indices = [Index(value = ["deviceId", "unixSeconds"])],
)
internal data class BeetSystemEventEntity(
    val deviceId: String,
    val seqNo: Long,
    val eventType: String,
    val reason: Int,
    val bootId: Long,
    val uptimeSeconds: Long,
    val unixSeconds: Long,
    val batteryMillivolts: Int,
    val peerAddress: String,
    val peerAddressType: Int,
    val knownPeer: Boolean,
    val detail: Long,
)

internal fun BeetSystemEvent.toEntity(deviceId: String): BeetSystemEventEntity =
    BeetSystemEventEntity(
        deviceId = deviceId,
        seqNo = sequenceNumber,
        eventType = eventType,
        reason = reason,
        bootId = bootId,
        uptimeSeconds = uptimeSeconds,
        unixSeconds = unixSeconds,
        batteryMillivolts = batteryMillivolts,
        peerAddress = peerAddress,
        peerAddressType = peerAddressType,
        knownPeer = knownPeer,
        detail = detail,
    )

internal fun BeetSystemEventEntity.toModel(): BeetSystemEvent =
    BeetSystemEvent(
        sequenceNumber = seqNo,
        eventType = eventType,
        reason = reason,
        bootId = bootId,
        uptimeSeconds = uptimeSeconds,
        unixSeconds = unixSeconds,
        batteryMillivolts = batteryMillivolts,
        peerAddress = peerAddress,
        peerAddressType = peerAddressType,
        knownPeer = knownPeer,
        detail = detail,
    )

@Entity(tableName = "sync_state", primaryKeys = ["deviceId", "kind"])
internal data class BeetSyncStateEntity(
    val deviceId: String,
    val kind: String,
    val watermarkSeq: Long,
)
