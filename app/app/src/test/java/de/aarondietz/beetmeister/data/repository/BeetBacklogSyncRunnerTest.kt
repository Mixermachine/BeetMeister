package de.aarondietz.beetmeister.data.repository

import de.aarondietz.beetmeister.model.event.BeetHistorySummary
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetSystemHistorySummary
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.stream.BeetEventSyncPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeetBacklogSyncRunnerTest {
    @Test
    fun downloadsNewestEventsFirstAndStopsAtRetentionBoundary() = runBlocking {
        val now = 5_000_000L
        val cutoff = now - RETENTION_SECONDS
        val fetched = mutableListOf<Long>()
        val ingested = mutableListOf<Long>()
        val delays = mutableListOf<Long>()
        val runner = runner(
            nowUnixSeconds = { now },
            sleep = { delays += it },
        )

        val downloaded = runner.run(
            input = BeetBacklogSyncInput(
                wateringSummary = BeetHistorySummary(
                    latestSequenceNumber = 5,
                    eventCount = 5,
                    pairTotalsSeconds = listOf(0, 0, 0, 0, 0, 0, 0, 0),
                ),
                systemSummary = null,
                existingWateringSequences = emptySet(),
                existingSystemSequences = emptySet(),
                limit = 64,
            ),
            isConnected = { true },
            isPauseRequested = { false },
            onProgress = {},
            onWateringEvent = { ingested += it.sequenceNumber },
            onSystemEvent = {},
            fetchWateringEvent = { sequence ->
                fetched += sequence
                val timestamp = when (sequence) {
                    5L -> cutoff + 300
                    4L -> cutoff + 200
                    3L -> cutoff - 1
                    else -> cutoff - 1000
                }
                BeetBacklogFetchResult(
                    status = BeetBacklogFetchStatus.Accepted,
                    event = wateringEvent(sequence = sequence, endedAtUnixSeconds = timestamp),
                )
            },
            fetchSystemEvent = { error("system stream is not used in this test") },
        )

        assertEquals(listOf(5L, 4L, 3L), fetched)
        assertEquals(listOf(5L, 4L), ingested)
        assertEquals(2, downloaded)
        assertTrue(delays.contains(BURST_DELAY_MS))
    }

    @Test
    fun pausesAndResumesWhenCommandPathRequestsIt() = runBlocking {
        var pauseReads = 0
        val phases = mutableListOf<BeetEventSyncPhase>()
        val delays = mutableListOf<Long>()
        val runner = runner(sleep = { delays += it })

        val downloaded = runner.run(
            input = BeetBacklogSyncInput(
                wateringSummary = BeetHistorySummary(
                    latestSequenceNumber = 1,
                    eventCount = 1,
                    pairTotalsSeconds = listOf(0, 0, 0, 0, 0, 0, 0, 0),
                ),
                systemSummary = null,
                existingWateringSequences = emptySet(),
                existingSystemSequences = emptySet(),
                limit = 64,
            ),
            isConnected = { true },
            isPauseRequested = {
                pauseReads += 1
                pauseReads == 1
            },
            onProgress = { phases += it.phase },
            onWateringEvent = {},
            onSystemEvent = {},
            fetchWateringEvent = {
                BeetBacklogFetchResult(
                    status = BeetBacklogFetchStatus.Accepted,
                    event = wateringEvent(sequence = 1, endedAtUnixSeconds = 999_900),
                )
            },
            fetchSystemEvent = { error("system stream is not used in this test") },
        )

        assertEquals(1, downloaded)
        assertTrue(phases.contains(BeetEventSyncPhase.PausedForCommand))
        assertTrue(phases.contains(BeetEventSyncPhase.CatchingUp))
        assertTrue(delays.contains(PAUSE_POLL_DELAY_MS))
    }

    @Test
    fun retriesSameSequenceAfterBusyOrRateLimitedWithoutSkipping() = runBlocking {
        val fetched = mutableListOf<Long>()
        val delays = mutableListOf<Long>()
        var busyReturned = false
        val runner = runner(sleep = { delays += it })

        val downloaded = runner.run(
            input = BeetBacklogSyncInput(
                wateringSummary = BeetHistorySummary(
                    latestSequenceNumber = 3,
                    eventCount = 3,
                    pairTotalsSeconds = listOf(0, 0, 0, 0, 0, 0, 0, 0),
                ),
                systemSummary = null,
                existingWateringSequences = emptySet(),
                existingSystemSequences = emptySet(),
                limit = 64,
            ),
            isConnected = { true },
            isPauseRequested = { false },
            onProgress = {},
            onWateringEvent = {},
            onSystemEvent = {},
            fetchWateringEvent = { sequence ->
                fetched += sequence
                if (sequence == 3L && !busyReturned) {
                    busyReturned = true
                    BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Busy)
                } else {
                    BeetBacklogFetchResult(
                        status = BeetBacklogFetchStatus.Accepted,
                        event = wateringEvent(sequence = sequence, endedAtUnixSeconds = 999_900),
                    )
                }
            },
            fetchSystemEvent = { error("system stream is not used in this test") },
        )

        assertEquals(listOf(3L, 3L, 2L, 1L), fetched)
        assertEquals(3, downloaded)
        assertTrue(delays.contains(CONGESTION_DELAY_MS))
    }

    @Test
    fun retriesTransientFailureAndAdvancesAfterRetryBudget() = runBlocking {
        val fetched = mutableListOf<Long>()
        val ingested = mutableListOf<Long>()
        var failedOnce = false
        val runner = runner()

        val downloaded = runner.run(
            input = BeetBacklogSyncInput(
                wateringSummary = BeetHistorySummary(
                    latestSequenceNumber = 3,
                    eventCount = 3,
                    pairTotalsSeconds = listOf(0, 0, 0, 0, 0, 0, 0, 0),
                ),
                systemSummary = null,
                existingWateringSequences = emptySet(),
                existingSystemSequences = emptySet(),
                limit = 64,
            ),
            isConnected = { true },
            isPauseRequested = { false },
            onProgress = {},
            onWateringEvent = { ingested += it.sequenceNumber },
            onSystemEvent = {},
            fetchWateringEvent = { sequence ->
                fetched += sequence
                when (sequence) {
                    3L -> {
                        if (!failedOnce) {
                            failedOnce = true
                            BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
                        } else {
                            BeetBacklogFetchResult(
                                status = BeetBacklogFetchStatus.Accepted,
                                event = wateringEvent(sequence = 3L, endedAtUnixSeconds = 999_920),
                            )
                        }
                    }
                    2L -> BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
                    1L -> BeetBacklogFetchResult(
                        status = BeetBacklogFetchStatus.Accepted,
                        event = wateringEvent(sequence = 1L, endedAtUnixSeconds = 999_800),
                    )
                    else -> error("unexpected sequence: $sequence")
                }
            },
            fetchSystemEvent = { error("system stream is not used in this test") },
        )

        assertEquals(listOf(3L, 3L, 2L, 2L, 1L), fetched)
        assertEquals(listOf(3L, 1L), ingested)
        assertEquals(2, downloaded)
    }

    @Test
    fun ingestsAndCountsAcceptedSystemEventsWithoutValidWallClockTime() = runBlocking {
        val fetched = mutableListOf<Long>()
        val ingested = mutableListOf<Long>()
        val runner = runner()

        val downloaded = runner.run(
            input = BeetBacklogSyncInput(
                wateringSummary = null,
                systemSummary = BeetSystemHistorySummary(
                    latestSequenceNumber = 2,
                    eventCount = 2,
                ),
                existingWateringSequences = emptySet(),
                existingSystemSequences = emptySet(),
                limit = 64,
            ),
            isConnected = { true },
            isPauseRequested = { false },
            onProgress = {},
            onWateringEvent = {},
            onSystemEvent = { ingested += it.sequenceNumber },
            fetchWateringEvent = { error("watering stream is not used in this test") },
            fetchSystemEvent = { sequence ->
                fetched += sequence
                BeetBacklogFetchResult(
                    status = BeetBacklogFetchStatus.Accepted,
                    event = systemEvent(sequence = sequence, unixSeconds = 0L),
                )
            },
        )

        assertEquals(listOf(2L, 1L), fetched)
        assertEquals(listOf(2L, 1L), ingested)
        assertEquals(2, downloaded)
    }

    private fun runner(
        nowUnixSeconds: () -> Long = { 1_000_000L },
        sleep: suspend (Long) -> Unit = {},
    ) = BeetBacklogSyncRunner(
        config = BeetBacklogSyncConfig(
            retentionSeconds = RETENTION_SECONDS,
            initialBatchSize = 2,
            maxBatchSize = 16,
            batchGrowthStep = 2,
            burstDelayMs = BURST_DELAY_MS,
            pausePollDelayMs = PAUSE_POLL_DELAY_MS,
            congestionDelayMs = CONGESTION_DELAY_MS,
            transientFailurePerSequenceLimit = 2,
        ),
        nowUnixSeconds = nowUnixSeconds,
        sleep = sleep,
    )

    private fun wateringEvent(sequence: Long, endedAtUnixSeconds: Long): BeetWateringEvent =
        BeetWateringEvent(
            sequenceNumber = sequence,
            pairIndex = 1,
            bootId = 7L,
            triggerSource = 1,
            startedAtUnixSeconds = endedAtUnixSeconds - 10,
            endedAtUnixSeconds = endedAtUnixSeconds,
            moistureBeforePercent = 45,
            moistureAfterPercent = 55,
            sensorBeforeMillivolts = 1450,
            sensorAfterMillivolts = 920,
            requestedDurationSeconds = 10,
            actualDurationSeconds = 10,
            stopReason = 0,
            blockReason = 0,
            batteryStartMillivolts = 3340,
            batteryEndMillivolts = 3330,
            startedUptimeSeconds = 100L,
            endedUptimeSeconds = 110L,
        )

    private fun systemEvent(sequence: Long, unixSeconds: Long): BeetSystemEvent =
        BeetSystemEvent(
            sequenceNumber = sequence,
            eventType = "BLE_CONNECT",
            reason = 0,
            bootId = 7L,
            uptimeSeconds = 123L,
            unixSeconds = unixSeconds,
            batteryMillivolts = 3340,
            peerAddress = "AA:BB:CC:DD:EE:FF",
            peerAddressType = 1,
            knownPeer = true,
            detail = 0L,
        )

    private companion object {
        private const val RETENTION_SECONDS = 30L * 24L * 60L * 60L
        private const val BURST_DELAY_MS = 120L
        private const val PAUSE_POLL_DELAY_MS = 200L
        private const val CONGESTION_DELAY_MS = 500L
    }
}
