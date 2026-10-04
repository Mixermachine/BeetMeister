package de.aarondietz.beetmeister.ui.feature.battery

import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryHistoryChartDataTest {

    // --------------- helpers ---------------

    private fun event(
        sequenceNumber: Long,
        batteryMillivolts: Int,
        unixSeconds: Long = 0,
        uptimeSeconds: Long = 10,
    ) = BeetSystemEvent(
        sequenceNumber = sequenceNumber,
        eventType = "STARTUP",
        reason = 0,
        bootId = 1,
        uptimeSeconds = uptimeSeconds,
        unixSeconds = unixSeconds,
        batteryMillivolts = batteryMillivolts,
        peerAddress = "",
        peerAddressType = 0,
        knownPeer = false,
        detail = 0,
    )

    // --------------- tests ---------------

    @Test
    fun sortsBySequenceNumber() {
        val data = buildBatteryPoints(
            listOf(
                event(sequenceNumber = 3, batteryMillivolts = 3350),
                event(sequenceNumber = 1, batteryMillivolts = 3310),
                event(sequenceNumber = 2, batteryMillivolts = 3320),
            ),
        )

        assertEquals(3, data.size)
        assertEquals(3310, data[0].millivolts)
        assertEquals(3320, data[1].millivolts)
        assertEquals(3350, data[2].millivolts)
    }

    @Test
    fun filtersZeroVoltages() {
        val data = buildBatteryPoints(
            listOf(
                event(sequenceNumber = 1, batteryMillivolts = 0),
                event(sequenceNumber = 2, batteryMillivolts = 3320),
                event(sequenceNumber = 3, batteryMillivolts = 0),
            ),
        )

        assertEquals(1, data.size)
        assertEquals(3320, data[0].millivolts)
    }

    @Test
    fun emptyListReturnsEmpty() {
        val data = buildBatteryPoints(emptyList())

        assertEquals(0, data.size)
    }

    @Test
    fun singleValidEventReturnsOnePoint() {
        val data = buildBatteryPoints(
            listOf(event(sequenceNumber = 1, batteryMillivolts = 3400)),
        )

        assertEquals(1, data.size)
        assertEquals(3400, data[0].millivolts)
    }

    @Test
    fun uptimeLabelWhenUnixNotSet() {
        val data = buildBatteryPoints(
            listOf(
                event(sequenceNumber = 1, batteryMillivolts = 3330, unixSeconds = 0, uptimeSeconds = 42),
            ),
        )

        assertEquals("42s", data[0].timestampLabel)
    }

    @Test
    fun hhmmLabelWhenUnixValid() {
        // unixSeconds = 3661 → 01:01:01 → day fraction = 3661 → 01:01
        val data = buildBatteryPoints(
            listOf(
                event(sequenceNumber = 1, batteryMillivolts = 3330, unixSeconds = 3661),
            ),
        )

        assertEquals("01:01", data[0].timestampLabel)
    }

    @Test
    fun hhmmLabelMidnight() {
        val data = buildBatteryPoints(
            listOf(
                event(sequenceNumber = 1, batteryMillivolts = 3330, unixSeconds = 86400), // exactly 00:00 UTC
            ),
        )

        assertEquals("00:00", data[0].timestampLabel)
    }

    @Test
    fun mixedLabelsUnixAndUptime() {
        val data = buildBatteryPoints(
            listOf(
                event(sequenceNumber = 1, batteryMillivolts = 3300, unixSeconds = 3600, uptimeSeconds = 5), // unix
                event(sequenceNumber = 2, batteryMillivolts = 3310, unixSeconds = 0, uptimeSeconds = 120), // uptime
            ),
        )

        assertEquals(2, data.size)
        assertEquals("01:00", data[0].timestampLabel)
        assertEquals("120s", data[1].timestampLabel)
    }
}
