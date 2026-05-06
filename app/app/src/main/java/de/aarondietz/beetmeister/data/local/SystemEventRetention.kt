package de.aarondietz.beetmeister.data.local

import de.aarondietz.beetmeister.model.event.BeetSystemEvent

internal fun shouldRetainSystemEvent(
    event: BeetSystemEvent,
    cutoffUnixSeconds: Long,
): Boolean =
    event.bootId > 0L && (!event.timeValid || event.unixSeconds >= cutoffUnixSeconds)

internal fun mergeRetainedSystemEvents(
    current: List<BeetSystemEvent>,
    incoming: List<BeetSystemEvent>,
    cutoffUnixSeconds: Long,
): List<BeetSystemEvent> =
    (current + incoming)
        .filter { event -> shouldRetainSystemEvent(event, cutoffUnixSeconds) }
        .associateBy { event -> event.sequenceNumber }
        .values
        .sortedByDescending { event -> event.sequenceNumber }
