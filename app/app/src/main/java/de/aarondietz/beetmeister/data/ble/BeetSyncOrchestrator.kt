package de.aarondietz.beetmeister.data.ble

import android.os.SystemClock
import de.aarondietz.beetmeister.data.local.BeetEventStore
import de.aarondietz.beetmeister.data.local.BeetEventStores
import de.aarondietz.beetmeister.data.local.mergeRetainedSystemEvents
import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.data.repository.BeetBacklogFetchResult
import de.aarondietz.beetmeister.data.repository.BeetBacklogFetchStatus
import de.aarondietz.beetmeister.data.repository.BeetBacklogSyncConfig
import de.aarondietz.beetmeister.data.repository.BeetBacklogSyncInput
import de.aarondietz.beetmeister.data.repository.BeetBacklogSyncRunner
import de.aarondietz.beetmeister.data.repository.BeetRepositoryCallbacks
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.stream.BeetEventSyncPhase
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Event-sync orchestration extracted from BeetGattSessionCoordinator (Phase 2d of the
 * coordinator split plan): background sync trigger, Tier 2 burst window loop with
 * per-device watermarks, pre-v19 legacy backlog fallback, controller time sync,
 * retention-merged UI state updates, and the burst ingest buffers with their
 * recomposition-damping flush gate. Delegates the stream command/response cycle to
 * the thin [BeetEventSyncEngine] over the runtime command seam.
 *
 * Pure relocation: no protocol, timing, watermark, or UI batching changes.
 */
internal class BeetSyncOrchestrator(
    private val host: BeetRepositoryCallbacks,
    private val pump: BeetCommandPump,
    private val engine: BeetEventSyncEngine,
    private val link: BeetGattLink,
    private val maintenanceUpdater: BeetMaintenanceUpdater,
) {
    val router get() = engine.router
    private val store: BeetEventStore by lazy { BeetEventStores.get(host.appContext) }
    private var eventSyncJob: Job? = null
    private val burstWateringBuffer = ArrayList<BeetWateringEvent>()
    private val burstSystemBuffer = ArrayList<BeetSystemEvent>()
    private val burstLock = Any()
    /* Burst UI coalescing: state emissions are rate-limited during streams so
       the Events screen does not recompose multi-thousand-item lists several
       times per second (this recomposition storm fed a rare Compose SlotTable
       dispose crash). Persistence keeps its own batch cadence; stream_end and
       abort paths flush unconditionally, so no event can be stranded. */
    private var lastBurstUiFlushElapsedMs = 0L

    internal fun cancelEventSyncJob() {
        eventSyncJob?.cancel()
        eventSyncJob = null
    }

    internal fun startBackgroundEventSync(force: Boolean = false, limit: Int = MAX_BACKGROUND_EVENT_DOWNLOAD) {
        if (maintenanceUpdater.isUploadActive) {
            BeetLog.d(TAG, "Skipping background event sync because maintenance update is active")
            return
        }
        if (!force && eventSyncJob?.isActive == true) {
            return
        }
        eventSyncJob?.cancel()
        eventSyncJob = host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            if (!synchronizeControllerTimeIfNeeded()) {
                val deviceState = host.state.value.deviceState
                BeetLog.w(TAG) {
                    "startBackgroundEventSync aborted because controller time is unavailable " +
                        "bootId=${deviceState?.bootId} timeValid=${deviceState?.timeValid} syncedTimeBootId=${host.session.syncedTimeBootId}"
                }
                host.updateState {
                    it.copy(
                        calibrationsRefreshing = false,
                        eventsLoading = false,
                        eventSync = BeetEventSyncState(
                            active = false,
                            transferred = 0,
                            total = 0,
                            phase = BeetEventSyncPhase.PausedForCommand,
                        ),
                        valveConfigRefreshing = false,
                        wateringIntervalRefreshing = false,
                    )
                }
                return@launch
            }
            val deviceId = host.state.value.controllerInfo?.deviceId ?: return@launch
            val cachedWatering = store.loadWateringEvents(deviceId)
            val cachedSystem = store.loadSystemEvents(deviceId)
            host.updateState {
                it.copy(
                    recentEvents = mergeWateringEvents(it.recentEvents, cachedWatering),
                    systemEvents = mergeSystemEvents(it.systemEvents, cachedSystem),
                    eventsLoading = true,
                    eventSync = BeetEventSyncState(active = true, phase = BeetEventSyncPhase.CatchingUp),
                )
            }

            val wateringSummary = runCatching { pump.sendSyncCommand(BeetJsonCodec.getHistorySummary()) }.getOrNull()?.historySummary
            val systemSummary = runCatching { pump.sendSyncCommand(BeetJsonCodec.getSystemHistorySummary()) }.getOrNull()?.systemHistorySummary
            host.updateState {
                it.copy(
                    historySummary = wateringSummary ?: it.historySummary,
                    systemHistorySummary = systemSummary ?: it.systemHistorySummary,
                )
            }

            /* Request high connection priority to reduce connection interval during bulk event sync */
            link.requestHighConnectionPriority()

            try {
                /*
                 * Coalesce event ingestion into the repository state in batches.
                 * Per-event state updates during a bulk backlog sync (thousands of events)
                 * cause a recomposition storm on the UI thread which can race with
                 * AndroidComposeView.draw (performMeasureAndLayout crash). SQLite caching
                 * stays per-event; only the state emission is batched.
                 */
                when (runBurstEventSync(wateringSummary, systemSummary)) {
                    BeetBurstSyncOutcome.Completed, BeetBurstSyncOutcome.Aborted -> Unit
                    BeetBurstSyncOutcome.Legacy -> runLegacyBacklogSync(deviceId, wateringSummary, systemSummary, limit)
                }
            } finally {
                /* Restore balanced connection priority when event sync finishes or is cancelled */
                link.restoreBalancedConnectionPriority()
            }
            host.updateState { state ->
                state.copy(
                    calibrationsRefreshing = false,
                    eventsLoading = false,
                    eventSync = BeetEventSyncState(),
                    valveConfigRefreshing = false,
                    wateringIntervalRefreshing = false,
                )
            }
        }
    }


    internal enum class BeetBurstSyncOutcome { Completed, Legacy, Aborted }

    /**
     * Tier 2 burst path: one stream_events command per kind; records arrive as
     * state-stream notifications consumed by handleStatePayload into the burst
     * buffers. Returns Legacy when the controller does not understand the
     * command (pre-v19 firmware), Aborted when cancelled mid-stream (partial
     * progress stays cached; the next sync trigger resumes), otherwise Completed.
     */
    private suspend fun runBurstEventSync(
        wateringSummary: de.aarondietz.beetmeister.model.event.BeetHistorySummary?,
        systemSummary: de.aarondietz.beetmeister.model.event.BeetSystemHistorySummary?,
    ): BeetBurstSyncOutcome {
        resetBurstState()
        val deviceId = host.state.value.controllerInfo?.deviceId ?: return BeetBurstSyncOutcome.Legacy

        /* Resume each kind at its persisted watermark: every synced event is
           already cached on the phone, so re-streaming the controller ring
           from sequence 1 is pure waste. A watermark ahead of the controller
           means the sequence numbers regressed (factory reset / reflash), in
           which case we fall back to one full resync. */
        /* Ring head per kind: old records are overwritten, so streaming below
           the oldest readable sequence only burns gap-skip round trips. */
        fun ringOldest(latest: Long, count: Int): Long =
            if (latest <= 0L || count <= 0) 1L else maxOf(1L, latest - count.toLong() + 1L)
        val oldestWatering = wateringSummary?.let { ringOldest(it.latestSequenceNumber, it.eventCount) } ?: 1L
        val oldestSystem = systemSummary?.let { ringOldest(it.latestSequenceNumber, it.eventCount) } ?: 1L
        var startWatering = maxOf(1L, store.loadSyncWatermark(deviceId, BeetStreamKind.WATERING.wireName), oldestWatering)
        var startSystem = maxOf(1L, store.loadSyncWatermark(deviceId, BeetStreamKind.SYSTEM.wireName), oldestSystem)
        var latestWatering = 0L
        var latestSystem = 0L
        fun pendingSince(start: Long, latest: Long): Long =
            if (latest == 0L) 0L else maxOf(0L, latest - start + 1L)
        fun publishTotal() {
            val pending = pendingSince(startWatering, latestWatering) + pendingSince(startSystem, latestSystem)
            host.updateState { it.copy(eventSync = it.eventSync.copy(total = pending.toInt())) }
        }

        for (kind in arrayOf(BeetStreamKind.WATERING, BeetStreamKind.SYSTEM)) {
            var cursor = if (kind == BeetStreamKind.WATERING) startWatering else startSystem
            var attempts = 0
            var needsFullResync = false
            while (true) {
                attempts += 1
                val run = engine.streamEvents(
                    kind = kind,
                    fromSeq = cursor,
                    maxEvents = BURST_WINDOW_EVENTS,
                    isCancelled = { pump.syncPauseRequested || maintenanceUpdater.isUploadActive },
                    isConnected = { host.state.value.connection.phase == BeetConnectionPhase.Connected },
                    onAck = { ack ->
                        if (kind == BeetStreamKind.WATERING) latestWatering = ack.latestSeq else latestSystem = ack.latestSeq
                        if (!needsFullResync && cursor > ack.latestSeq + 1L) {
                            needsFullResync = true
                        }
                        publishTotal()
                    },
                )
                when (run) {
                    BeetStreamRun.Unsupported -> {
                        synchronized(burstLock) { flushBurstBuffers() }
                        resetBurstState()
                        return BeetBurstSyncOutcome.Legacy
                    }
                    is BeetStreamRun.Completed -> {
                        if (needsFullResync) {
                            needsFullResync = false
                            cursor = 1L
                            if (kind == BeetStreamKind.WATERING) startWatering = 1L else startSystem = 1L
                            publishTotal()
                            continue
                        }
                        cursor = run.nextCursor
                        if (cursor > 1L) {
                            store.saveSyncWatermark(deviceId, kind.wireName, cursor - 1L)
                        }
                        val latest = if (kind == BeetStreamKind.WATERING) latestWatering else latestSystem
                        if (cursor > latest || attempts >= MAX_BURST_WINDOWS_PER_KIND) {
                            break
                        }
                        /* The controller pump ended this window with stream_end
                           complete; arm the next window to continue the backlog. */
                    }
                    is BeetStreamRun.Cancelled -> {
                        synchronized(burstLock) { flushBurstBuffers() }
                        if (attempts >= MAX_BURST_ATTEMPTS_PER_KIND ||
                            host.state.value.connection.phase != BeetConnectionPhase.Connected ||
                            maintenanceUpdater.isUploadActive
                        ) {
                            resetBurstState()
                            return BeetBurstSyncOutcome.Aborted
                        }
                        pump.awaitSyncResumeIfNeeded()
                        cursor = run.nextCursor
                    }
                    is BeetStreamRun.Disconnected -> {
                        synchronized(burstLock) { flushBurstBuffers() }
                        resetBurstState()
                        return BeetBurstSyncOutcome.Aborted
                    }
                }
            }
            synchronized(burstLock) { flushBurstBuffers() }
        }
        return BeetBurstSyncOutcome.Completed
    }

    private suspend fun runLegacyBacklogSync(
        deviceId: String,
        wateringSummary: de.aarondietz.beetmeister.model.event.BeetHistorySummary?,
        systemSummary: de.aarondietz.beetmeister.model.event.BeetSystemHistorySummary?,
        limit: Int,
    ) {
        /*
         * Pre-v19 fallback path: adaptive per-sequence stop-and-wait fetching.
         * Coalesce event ingestion into the repository state in batches (see the
         * performMeasureAndLayout crash note on the original extraction).
         */
            val wateringBuffer = ArrayList<BeetWateringEvent>(EVENT_UI_BATCH_SIZE)
            val systemBuffer = ArrayList<BeetSystemEvent>(EVENT_UI_BATCH_SIZE)
            fun flushIngestedEvents() {
                if (wateringBuffer.isEmpty() && systemBuffer.isEmpty()) return
                val watering = wateringBuffer.toList()
                val system = systemBuffer.toList()
                wateringBuffer.clear()
                systemBuffer.clear()
                runCatching { store.saveWateringEvents(deviceId, watering) }
                runCatching { store.saveSystemEvents(deviceId, system) }
                host.updateState { state ->
                    state.copy(
                        recentEvents = if (watering.isEmpty()) state.recentEvents else mergeWateringEvents(state.recentEvents, watering),
                        systemEvents = if (system.isEmpty()) state.systemEvents else mergeSystemEvents(state.systemEvents, system),
                    )
                }
            }

            val runner = BeetBacklogSyncRunner(
            config = BeetBacklogSyncConfig(
                retentionSeconds = EVENT_RETENTION_SECONDS,
                initialBatchSize = INITIAL_SYNC_BATCH_SIZE,
                maxBatchSize = MAX_SYNC_BATCH_SIZE,
                batchGrowthStep = SYNC_BATCH_GROWTH_STEP,
                burstDelayMs = SYNC_BURST_DELAY_MS,
                pausePollDelayMs = BeetCommandPump.SYNC_PAUSE_POLL_MS,
                congestionDelayMs = SYNC_CONGESTION_DELAY_MS,
                transientFailurePerSequenceLimit = SYNC_TRANSIENT_FAILURE_LIMIT,
                maxConsecutiveNotFoundLimit = SYNC_CONSECUTIVE_NOT_FOUND_LIMIT,
            ),
            nowUnixSeconds = { System.currentTimeMillis() / 1000L },
            sleep = { delay(it) },
        )

        runner.run(
            input = BeetBacklogSyncInput(
                wateringSummary = wateringSummary,
                systemSummary = systemSummary,
                existingWateringSequences = host.state.value.recentEvents.map { event -> event.sequenceNumber }.toSet(),
                existingSystemSequences = host.state.value.systemEvents.map { event -> event.sequenceNumber }.toSet(),
                limit = limit,
            ),
            isConnected = { host.state.value.connection.phase == BeetConnectionPhase.Connected },
            isPauseRequested = { pump.syncPauseRequested },
            onProgress = { progress ->
                host.updateState {
                    it.copy(
                        eventSync = it.eventSync.copy(
                            active = progress.active,
                            transferred = progress.transferred,
                            total = progress.total,
                            phase = progress.phase,
                        ),
                    )
                }
            },
            onWateringEvent = { event ->
                wateringBuffer += event
                if (wateringBuffer.size >= EVENT_UI_BATCH_SIZE) flushIngestedEvents()
            },
            onSystemEvent = { event ->
                systemBuffer += event
                if (systemBuffer.size >= EVENT_UI_BATCH_SIZE) flushIngestedEvents()
            },
            fetchWateringEvent = { sequence -> fetchWateringEventForSync(sequence) },
            fetchSystemEvent = { sequence -> fetchSystemEventForSync(sequence) },
        )

        /* Publish any events still buffered when the sync loop finishes */
        flushIngestedEvents()
    }

    private suspend fun fetchWateringEventForSync(sequence: Long): BeetBacklogFetchResult<BeetWateringEvent> {
        val result = runCatching { pump.sendSyncCommand(BeetJsonCodec.getEvent(sequence)) }.getOrNull()
            ?: run {
                BeetLog.w(TAG, "fetchWateringEventForSync seq=$sequence command failed before a result was returned")
                return BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
            }
        val reason = result.reason.lowercase()
        val fetchResult = when {
            result.status == "accepted" && result.event != null ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Accepted, event = result.event)
            reason == "busy" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Busy)
            reason == "rate_limited" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.RateLimited)
            reason == "event_not_found" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.NotFound)
            else ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
        }
        BeetLog.d(TAG) { "fetchWateringEventForSync seq=$sequence -> status=${fetchResult.status}" }
        return fetchResult
    }

    private suspend fun fetchSystemEventForSync(sequence: Long): BeetBacklogFetchResult<BeetSystemEvent> {
        val result = runCatching { pump.sendSyncCommand(BeetJsonCodec.getSystemEvent(sequence)) }.getOrNull()
            ?: run {
                BeetLog.w(TAG, "fetchSystemEventForSync seq=$sequence command failed before a result was returned")
                return BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
            }
        val reason = result.reason.lowercase()
        val fetchResult = when {
            result.status == "accepted" && result.systemEvent != null ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Accepted, event = result.systemEvent)
            reason == "busy" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Busy)
            reason == "rate_limited" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.RateLimited)
            reason == "event_not_found" ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.NotFound)
            else ->
                BeetBacklogFetchResult(status = BeetBacklogFetchStatus.Failed)
        }
        BeetLog.d(TAG) { "fetchSystemEventForSync seq=$sequence -> status=${fetchResult.status}" }
        return fetchResult
    }

    fun mergeWateringEvents(current: List<BeetWateringEvent>, incoming: List<BeetWateringEvent>): List<BeetWateringEvent> =
        (current + incoming)
            .filter {
                it.bootId > 0L &&
                    (!it.timeValid || it.endedAtUnixSeconds >= ((System.currentTimeMillis() / 1000L) - EVENT_RETENTION_SECONDS))
            }
            .associateBy { it.sequenceNumber }
            .values
            .sortedByDescending { it.sequenceNumber }
            .let { if (it.size > EVENT_UI_MEMORY_CAP) it.take(EVENT_UI_MEMORY_CAP) else it }

    fun mergeSystemEvents(current: List<BeetSystemEvent>, incoming: List<BeetSystemEvent>): List<BeetSystemEvent> =
        mergeRetainedSystemEvents(
            current = current,
            incoming = incoming,
            cutoffUnixSeconds = (System.currentTimeMillis() / 1000L) - EVENT_RETENTION_SECONDS,
        ).let { if (it.size > EVENT_UI_MEMORY_CAP) it.take(EVENT_UI_MEMORY_CAP) else it }

    fun ingestWateringEvent(deviceId: String, event: BeetWateringEvent) {
        store.saveWateringEvents(deviceId, listOf(event))
        host.updateState { state -> state.copy(recentEvents = mergeWateringEvents(state.recentEvents, listOf(event))) }
    }

    fun ingestSystemEvent(deviceId: String, event: BeetSystemEvent) {
        store.saveSystemEvents(deviceId, listOf(event))
        host.updateState { state -> state.copy(systemEvents = mergeSystemEvents(state.systemEvents, listOf(event))) }
    }

    private suspend fun synchronizeControllerTimeIfNeeded(): Boolean {
        val deviceState = host.state.value.deviceState ?: return false
        if (deviceState.timeValid) {
            host.session.syncedTimeBootId = deviceState.bootId
            return true
        }
        if (deviceState.bootId > 0L && host.session.syncedTimeBootId == deviceState.bootId) {
            BeetLog.w(TAG, "synchronizeControllerTimeIfNeeded refusing duplicate set_time attempt for bootId=${deviceState.bootId}")
            return false
        }
        val unixSeconds = System.currentTimeMillis() / 1000L
        val result = runCatching { pump.sendCommand(BeetJsonCodec.setTime(unixSeconds)) }.getOrNull() ?: return false
        if (result.status == "accepted") {
            repeat(10) {
                val refreshed = host.state.value.deviceState
                if (refreshed?.timeValid == true) {
                    host.session.syncedTimeBootId = refreshed.bootId
                    return true
                }
                delay(150L)
            }
        }
        BeetLog.w(TAG, "synchronizeControllerTimeIfNeeded timed out waiting for time_valid after set_time")
        return false
    }


    fun ingestBurstWateringEvent(event: BeetWateringEvent) {
        synchronized(burstLock) {
            burstWateringBuffer += event
            if (burstWateringBuffer.size >= EVENT_UI_BATCH_SIZE && burstUiFlushAllowed()) flushBurstBuffers()
        }
    }

    fun ingestBurstSystemEvent(event: BeetSystemEvent) {
        synchronized(burstLock) {
            burstSystemBuffer += event
            if (burstSystemBuffer.size >= EVENT_UI_BATCH_SIZE && burstUiFlushAllowed()) flushBurstBuffers()
        }
    }

    /* Caller must hold [burstLock]. */
    private fun burstUiFlushAllowed(): Boolean =
        SystemClock.elapsedRealtime() - lastBurstUiFlushElapsedMs >= BURST_UI_FLUSH_MIN_INTERVAL_MS
    private fun flushBurstBuffers() {
        if (burstWateringBuffer.isEmpty() && burstSystemBuffer.isEmpty()) return
        val watering = burstWateringBuffer.toList()
        val system = burstSystemBuffer.toList()
        burstWateringBuffer.clear()
        burstSystemBuffer.clear()
        lastBurstUiFlushElapsedMs = SystemClock.elapsedRealtime()
        val deviceId = host.state.value.controllerInfo?.deviceId
        if (deviceId != null) {
            runCatching { store.saveWateringEvents(deviceId, watering) }
            runCatching { store.saveSystemEvents(deviceId, system) }
        }
        host.updateState { state ->
            val transferred = (state.eventSync.transferred + watering.size + system.size).coerceAtMost(state.eventSync.total)
            state.copy(
                recentEvents = if (watering.isEmpty()) state.recentEvents else mergeWateringEvents(state.recentEvents, watering),
                systemEvents = if (system.isEmpty()) state.systemEvents else mergeSystemEvents(state.systemEvents, system),
                eventSync = state.eventSync.copy(transferred = transferred),
            )
        }
    }

    private fun resetBurstState() {
        synchronized(burstLock) {
            burstWateringBuffer.clear()
            burstSystemBuffer.clear()
            lastBurstUiFlushElapsedMs = 0L
        }
        router.abort(BeetStreamKind.WATERING)
        router.abort(BeetStreamKind.SYSTEM)
    }


    private companion object {
        private const val TAG = "BeetGattSession"
        private const val MAX_BACKGROUND_EVENT_DOWNLOAD = 120
        /* Burst events per stream_events window. Large windows amortize the ack/end
           command round trips; the pump stays cancelable and resumable either way. */
        private const val BURST_WINDOW_EVENTS = 5000L
        /* Safety cap on windows per kind (5000 * 40 covers both full rings). */
        private const val MAX_BURST_WINDOWS_PER_KIND = 40
        private const val MAX_BURST_ATTEMPTS_PER_KIND = 4
        private const val INITIAL_SYNC_BATCH_SIZE = 1
        private const val MAX_SYNC_BATCH_SIZE = 8
        private const val SYNC_BATCH_GROWTH_STEP = 1
        private const val SYNC_BURST_DELAY_MS = 0L
        private const val SYNC_CONGESTION_DELAY_MS = 150L
        private const val SYNC_TRANSIENT_FAILURE_LIMIT = 2
        private const val SYNC_CONSECUTIVE_NOT_FOUND_LIMIT = 3
        private const val EVENT_RETENTION_SECONDS = 30L * 24L * 60L * 60L

        /* In-memory cap for the UI event lists (newest kept). The full history
           stays on the controller and in the per-device event cache; holding
           multi-thousand lists in state caused recomposition storms. */
        private const val EVENT_UI_MEMORY_CAP = 2000
        // Batch size for coalescing bulk-sync event ingestion into repository state.
        private const val EVENT_UI_BATCH_SIZE = 20
        // Minimum interval between burst UI state emissions (see lastBurstUiFlushElapsedMs).
        private const val BURST_UI_FLUSH_MIN_INTERVAL_MS = 500L
    }
}
