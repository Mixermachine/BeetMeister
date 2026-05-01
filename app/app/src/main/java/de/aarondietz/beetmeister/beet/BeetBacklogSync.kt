package de.aarondietz.beetmeister.beet

import kotlin.math.max
import kotlin.math.min

internal data class BeetBacklogSyncConfig(
    val retentionSeconds: Long,
    val initialBatchSize: Int,
    val maxBatchSize: Int,
    val batchGrowthStep: Int,
    val burstDelayMs: Long,
    val pausePollDelayMs: Long,
    val congestionDelayMs: Long,
    val transientFailurePerSequenceLimit: Int,
)

internal enum class BeetBacklogFetchStatus {
    Accepted,
    NotFound,
    Busy,
    RateLimited,
    Failed,
}

internal data class BeetBacklogFetchResult<T>(
    val status: BeetBacklogFetchStatus,
    val event: T? = null,
)

internal data class BeetBacklogProgress(
    val downloaded: Int,
    val total: Int,
    val active: Boolean,
    val phase: BeetEventSyncPhase,
)

internal data class BeetBacklogSyncInput(
    val wateringSummary: BeetHistorySummary?,
    val systemSummary: BeetSystemHistorySummary?,
    val existingWateringSequences: Set<Long>,
    val existingSystemSequences: Set<Long>,
    val limit: Int,
)

internal class BeetBacklogSyncRunner(
    private val config: BeetBacklogSyncConfig,
    private val nowUnixSeconds: () -> Long,
    private val sleep: suspend (Long) -> Unit,
) {
    suspend fun run(
        input: BeetBacklogSyncInput,
        isConnected: () -> Boolean,
        isPauseRequested: () -> Boolean,
        onProgress: (BeetBacklogProgress) -> Unit,
        onWateringEvent: (BeetWateringEvent) -> Unit,
        onSystemEvent: (BeetSystemEvent) -> Unit,
        fetchWateringEvent: suspend (Long) -> BeetBacklogFetchResult<BeetWateringEvent>,
        fetchSystemEvent: suspend (Long) -> BeetBacklogFetchResult<BeetSystemEvent>,
    ): Int {
        val existingWatering = input.existingWateringSequences.toMutableSet()
        val existingSystem = input.existingSystemSequences.toMutableSet()
        var wateringCursor = input.wateringSummary?.latestSequenceNumber ?: 0L
        var systemCursor = input.systemSummary?.latestSequenceNumber ?: 0L
        var wateringDone = wateringCursor <= 0L
        var systemDone = systemCursor <= 0L
        val maxBatchSize = max(config.initialBatchSize, min(config.maxBatchSize, input.limit))
        var batchSize = config.initialBatchSize
        val cutoffUnixSeconds = nowUnixSeconds() - config.retentionSeconds
        val estimatedWateringMissing = max(0, (input.wateringSummary?.eventCount ?: 0) - existingWatering.size)
        val estimatedSystemMissing = max(0, (input.systemSummary?.eventCount ?: 0) - existingSystem.size)
        var downloaded = 0
        val total = estimatedWateringMissing + estimatedSystemMissing
        val wateringFailureCounts = mutableMapOf<Long, Int>()
        val systemFailureCounts = mutableMapOf<Long, Int>()

        onProgress(BeetBacklogProgress(downloaded = 0, total = total, active = true, phase = BeetEventSyncPhase.CatchingUp))

        while ((!wateringDone || !systemDone) && isConnected()) {
            if (isPauseRequested()) {
                onProgress(BeetBacklogProgress(downloaded = downloaded, total = max(total, downloaded), active = true, phase = BeetEventSyncPhase.PausedForCommand))
                sleep(config.pausePollDelayMs)
                continue
            }

            var progressThisCycle = false
            var congestedThisCycle = false
            onProgress(BeetBacklogProgress(downloaded = downloaded, total = max(total, downloaded), active = true, phase = BeetEventSyncPhase.CatchingUp))

            if (!wateringDone) {
                val result = processBatch(
                    cursor = wateringCursor,
                    done = wateringDone,
                    existing = existingWatering,
                    batchSize = batchSize,
                    cutoffUnixSeconds = cutoffUnixSeconds,
                    failureCounts = wateringFailureCounts,
                    fetch = fetchWateringEvent,
                    eventTime = { it.endedAtUnixSeconds },
                    eventTimeValid = { it.timeValid },
                    onAcceptedEvent = onWateringEvent,
                )
                wateringCursor = result.nextCursor
                wateringDone = result.done
                progressThisCycle = progressThisCycle || result.progressMade
                congestedThisCycle = congestedThisCycle || result.congested
                downloaded += result.downloaded
            }

            if (!systemDone && !congestedThisCycle) {
                val result = processBatch(
                    cursor = systemCursor,
                    done = systemDone,
                    existing = existingSystem,
                    batchSize = batchSize,
                    cutoffUnixSeconds = cutoffUnixSeconds,
                    failureCounts = systemFailureCounts,
                    fetch = fetchSystemEvent,
                    eventTime = { it.unixSeconds },
                    eventTimeValid = { it.timeValid },
                    onAcceptedEvent = onSystemEvent,
                )
                systemCursor = result.nextCursor
                systemDone = result.done
                progressThisCycle = progressThisCycle || result.progressMade
                congestedThisCycle = congestedThisCycle || result.congested
                downloaded += result.downloaded
            }

            val complete = wateringDone && systemDone
            onProgress(
                BeetBacklogProgress(
                    downloaded = downloaded,
                    total = max(total, downloaded),
                    active = !complete,
                    phase = if (complete) BeetEventSyncPhase.Completed else BeetEventSyncPhase.CatchingUp,
                ),
            )

            if (complete) {
                break
            }

            if (congestedThisCycle) {
                batchSize = config.initialBatchSize
                sleep(config.congestionDelayMs)
            } else {
                batchSize = if (progressThisCycle) {
                    min(maxBatchSize, batchSize + config.batchGrowthStep)
                } else {
                    config.initialBatchSize
                }
                sleep(config.burstDelayMs)
            }
        }

        onProgress(
            BeetBacklogProgress(
                downloaded = downloaded,
                total = max(total, downloaded),
                active = false,
                phase = BeetEventSyncPhase.Completed,
            ),
        )
        return downloaded
    }

    private suspend fun <T> processBatch(
        cursor: Long,
        done: Boolean,
        existing: MutableSet<Long>,
        batchSize: Int,
        cutoffUnixSeconds: Long,
        failureCounts: MutableMap<Long, Int>,
        fetch: suspend (Long) -> BeetBacklogFetchResult<T>,
        eventTime: (T) -> Long,
        eventTimeValid: (T) -> Boolean,
        onAcceptedEvent: (T) -> Unit,
    ): StreamResult {
        if (done) {
            return StreamResult(nextCursor = cursor, done = true, progressMade = false, congested = false, downloaded = 0)
        }
        var nextCursor = cursor
        var nextDone = nextCursor <= 0L
        var inspected = 0
        var progressMade = false
        var congested = false
        var downloaded = 0

        while (!nextDone && inspected < batchSize && nextCursor > 0L) {
            val sequence = nextCursor
            if (existing.contains(sequence)) {
                nextCursor -= 1L
                inspected++
                continue
            }

            val result = fetch(sequence)
            when (result.status) {
                BeetBacklogFetchStatus.Accepted -> {
                    nextCursor -= 1L
                    inspected++
                    failureCounts.remove(sequence)
                    existing += sequence
                    val event = result.event ?: continue
                    if (!eventTimeValid(event)) {
                        continue
                    }
                    if (eventTime(event) < cutoffUnixSeconds) {
                        nextDone = true
                        break
                    }
                    onAcceptedEvent(event)
                    progressMade = true
                    downloaded++
                }
                BeetBacklogFetchStatus.NotFound -> {
                    nextCursor -= 1L
                    inspected++
                    failureCounts.remove(sequence)
                    existing += sequence
                }
                BeetBacklogFetchStatus.Busy,
                BeetBacklogFetchStatus.RateLimited,
                -> {
                    congested = true
                    break
                }
                BeetBacklogFetchStatus.Failed -> {
                    val failures = (failureCounts[sequence] ?: 0) + 1
                    if (failures >= config.transientFailurePerSequenceLimit) {
                        nextCursor -= 1L
                        inspected++
                        existing += sequence
                        failureCounts.remove(sequence)
                    } else {
                        failureCounts[sequence] = failures
                    }
                    break
                }
            }
        }

        if (nextCursor <= 0L) {
            nextDone = true
        }
        return StreamResult(
            nextCursor = nextCursor,
            done = nextDone,
            progressMade = progressMade,
            congested = congested,
            downloaded = downloaded,
        )
    }

    private data class StreamResult(
        val nextCursor: Long,
        val done: Boolean,
        val progressMade: Boolean,
        val congested: Boolean,
        val downloaded: Int,
    )
}
