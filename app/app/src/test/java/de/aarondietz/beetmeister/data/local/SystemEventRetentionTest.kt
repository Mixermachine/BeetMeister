package de.aarondietz.beetmeister.data.local

import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemEventRetentionTest {
    @Test
    fun unresolvedSystemEventIsRetainedWithoutWallClockTime() {
        val event = systemEvent(sequenceNumber = 7, bootId = 42, unixSeconds = 0)

        assertTrue(shouldRetainSystemEvent(event, cutoffUnixSeconds = 1_000_000L))
    }

    @Test
    fun oldResolvedSystemEventFallsOutOfRetention() {
        val event = systemEvent(sequenceNumber = 8, bootId = 42, unixSeconds = 10, timeValid = true)

        assertFalse(shouldRetainSystemEvent(event, cutoffUnixSeconds = 100L))
    }

    @Test
    fun mergeRetainedSystemEventsKeepsUnresolvedAndDeduplicatesBySequence() {
        val merged = mergeRetainedSystemEvents(
            current = listOf(
                systemEvent(sequenceNumber = 1, bootId = 40, unixSeconds = 0),
                systemEvent(sequenceNumber = 2, bootId = 41, unixSeconds = 1_200, timeValid = true),
            ),
            incoming = listOf(
                systemEvent(sequenceNumber = 1, bootId = 40, unixSeconds = 0),
                systemEvent(sequenceNumber = 3, bootId = 42, unixSeconds = 50, timeValid = true),
            ),
            cutoffUnixSeconds = 100L,
        )

        assertEquals(listOf(2L, 1L), merged.map { it.sequenceNumber })
    }

    private fun systemEvent(
        sequenceNumber: Long,
        bootId: Long,
        unixSeconds: Long,
        timeValid: Boolean = false,
    ) = BeetSystemEvent(
        sequenceNumber = sequenceNumber,
        eventType = "STARTUP",
        reason = 0,
        bootId = bootId,
        uptimeSeconds = 12,
        unixSeconds = if (timeValid) unixSeconds else 0L,
        batteryMillivolts = 3330,
        peerAddress = "",
        peerAddressType = 0,
        knownPeer = false,
        detail = 0,
    )
}
