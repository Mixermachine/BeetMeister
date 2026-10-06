package de.aarondietz.beetmeister.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.net.Uri
import android.os.SystemClock
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.BuildConfig
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.data.firmware.BeetFirmwareImagePackage
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
import de.aarondietz.beetmeister.data.repository.commandMessageForResult
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo
import de.aarondietz.beetmeister.model.controller.BeetPairCombined
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.displayedPairCount
import de.aarondietz.beetmeister.model.repository.isFollower
import de.aarondietz.beetmeister.model.repository.isLead
import de.aarondietz.beetmeister.model.repository.leadFor
import de.aarondietz.beetmeister.model.stream.BeetEventSyncPhase
import de.aarondietz.beetmeister.model.stream.BeetEventSyncState
import de.aarondietz.beetmeister.model.stream.BeetStateMessage
import de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary
import de.aarondietz.beetmeister.model.update.BeetMaintenanceStatus
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase
import de.aarondietz.beetmeister.model.update.isActiveMaintenancePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import kotlin.math.max

internal class BeetGattSessionCoordinator(
    private val host: BeetRepositoryCallbacks,
) : BeetMaintenanceLink, BeetRuntimeCommandLink, BeetGattLinkDelegate {
    private enum class ExpectedControllerAction {
        None,
        Reboot,
        FactoryReset,
    }

    private val strings get() = host.strings
    private val eventStore: BeetEventStore by lazy { BeetEventStores.get(host.appContext) }
    private val maintenanceUpdater = BeetMaintenanceUpdater(host, this)
    private val gattLink = BeetGattLink(host, this, maintenanceUpdater)
    private val commandPump = BeetCommandPump(host, gattLink)
    private var eventSyncJob: Job? = null
    private val runtimeCommands = BeetRuntimeCommands(host, this)
    private val streamRouter = BeetResponseRouter()
    private val eventSyncEngine = BeetEventSyncEngine(link = this, router = streamRouter)
    private val burstWateringBuffer = ArrayList<BeetWateringEvent>()
    private val burstSystemBuffer = ArrayList<BeetSystemEvent>()
    private val burstLock = Any()
    /* Burst UI coalescing: state emissions are rate-limited during streams so
       the Events screen does not recompose multi-thousand-item lists several
       times per second (this recomposition storm fed a rare Compose SlotTable
       dispose crash). Persistence keeps its own batch cadence; stream_end and
       abort paths flush unconditionally, so no event can be stranded. */
    private var lastBurstUiFlushElapsedMs = 0L
    private var expectedControllerAction: ExpectedControllerAction = ExpectedControllerAction.None
    private var expectedControllerActionUntilMs: Long = 0L

    fun close() {
        BeetLog.d(TAG, "close()")
        gattLink.disconnectGatt(clearSelection = false, reason = "repository close")
        eventSyncJob?.cancel()
        eventSyncJob = null
        maintenanceUpdater.shutdown()
    }

    fun clearCommandMessage() {
        host.clearCommandMessage()
    }

    // --- BeetMaintenanceLink (bridges the extracted maintenance engine back to this session) ---

    override val mtu: Int get() = gattLink.mtu
    override val gattResetAtMs: Long get() = gattLink.gattResetAtMs

    override fun cancelConnectionTimeout() {
        gattLink.cancelConnectionTimeout()
    }

    override fun suspendRuntimeSync(reason: String) {
        BeetLog.d(TAG) { "suspendRuntimeSync(reason=$reason)" }
        eventSyncJob?.cancel()
        eventSyncJob = null
        host.updateState { state ->
            state.copy(
                eventsLoading = false,
                eventSync = BeetEventSyncState(),
                valveConfigRefreshing = false,
                wateringIntervalRefreshing = false,
            )
        }
    }

    private fun setExpectedControllerAction(action: ExpectedControllerAction) {
        expectedControllerAction = action
        expectedControllerActionUntilMs = SystemClock.elapsedRealtime() + EXPECTED_CONTROLLER_ACTION_TIMEOUT_MS
    }

    private fun expectedControllerActionActive(): Boolean =
        expectedControllerAction != ExpectedControllerAction.None &&
            SystemClock.elapsedRealtime() <= expectedControllerActionUntilMs

    private fun clearCachedControllerHistory() {
        val deviceId = host.state.value.controllerInfo?.deviceId ?: return
        eventStore.clearDevice(deviceId)
        host.updateState { state ->
            state.copy(
                historySummary = null,
                systemHistorySummary = null,
                recentEvents = emptyList(),
                systemEvents = emptyList(),
                eventSync = BeetEventSyncState(),
            )
        }
    }

    fun sendRawCommand(payload: String): BeetCommandResult = runtimeCommands.sendRawCommand(payload)

    fun refreshEvents() {
        startBackgroundEventSync(limit = 10000)
    }

    fun refreshCalibrations() = runtimeCommands.refreshCalibrations()

    fun refreshHistorySummary() {
        startBackgroundEventSync(force = true)
    }

    fun loadRecentEvents(limit: Int = 50) {
        startBackgroundEventSync(force = true, limit = limit)
    }

    fun manualStart(pairIndex: Int, durationSeconds: Int?) = runtimeCommands.manualStart(pairIndex, durationSeconds)

    fun manualStop(pairIndex: Int) = runtimeCommands.manualStop(pairIndex)

    fun moistureTestStart(pairIndex: Int) = runtimeCommands.moistureTestStart(pairIndex)

    fun clearPairError(pairIndex: Int) = runtimeCommands.clearPairError(pairIndex)

    fun resetBlock(pairIndex: Int) = clearPairError(pairIndex)

    fun disablePair(pairIndex: Int) = runtimeCommands.disablePair(pairIndex)

    fun enablePair(pairIndex: Int) = runtimeCommands.enablePair(pairIndex)

    fun saveCalibration(pairIndex: Int, dryMillivolts: Int, wetMillivolts: Int) =
        runtimeCommands.saveCalibration(pairIndex, dryMillivolts, wetMillivolts)

    fun loadPairWiring(pairIndex: Int) = runtimeCommands.loadPairWiring(pairIndex)

    suspend fun fetchPairNamesInternal() = runtimeCommands.fetchPairNamesInternal()

    fun loadPairNames() = runtimeCommands.loadPairNames()

    fun storePairName(pairIndex: Int, name: String) = runtimeCommands.storePairName(pairIndex, name)

    suspend fun fetchPairCombinedInternal() = runtimeCommands.fetchPairCombinedInternal()

    fun refreshPairCombined() = runtimeCommands.refreshPairCombined()

    fun setPairSensorSource(pairIndex: Int, leadPairIndex: Int?) = runtimeCommands.setPairSensorSource(pairIndex, leadPairIndex)

    fun loadPairCombined(pairIndex: Int) = runtimeCommands.loadPairCombined(pairIndex)

    fun storePairCombined(pairIndex: Int, followersMask: Int) = runtimeCommands.storePairCombined(pairIndex, followersMask)

    fun loadPairConfig(pairIndex: Int) = runtimeCommands.loadPairConfig(pairIndex)

    fun storePairConfig(
        pairIndex: Int,
        targetLevel: de.aarondietz.beetmeister.model.controller.TargetMoistureLevel,
        durationMultiplier: Int,
    ) = runtimeCommands.storePairConfig(pairIndex, targetLevel, durationMultiplier)

    fun refreshValveConfig() = runtimeCommands.refreshValveConfig()

    fun refreshWateringInterval() = runtimeCommands.refreshWateringInterval()

    fun saveValveConfig(config: BeetValveConfig) = runtimeCommands.saveValveConfig(config)

    fun saveWateringInterval(seconds: Int) = runtimeCommands.saveWateringInterval(seconds)

    fun refreshMaxActivePumps() = runtimeCommands.refreshMaxActivePumps()

    fun storeMaxActivePumps(max: Int) = runtimeCommands.storeMaxActivePumps(max)

    fun previewValvePosition(pulseMicros: Int) = runtimeCommands.previewValvePosition(pulseMicros)

    fun openValve() = runtimeCommands.openValve()

    fun closeValve() = runtimeCommands.closeValve()

    fun rebootController() = runtimeCommands.rebootController()

    fun factoryResetController() = runtimeCommands.factoryResetController()

    fun runScheduler() = runtimeCommands.runScheduler()

    fun prepareBundledFirmware() = maintenanceUpdater.prepareBundledFirmware()

    fun prepareCustomFirmware(uri: android.net.Uri) = maintenanceUpdater.prepareCustomFirmware(uri)

    fun startMaintenanceUpdate() = maintenanceUpdater.startMaintenanceUpdate()

    fun abortMaintenanceUpdate() = maintenanceUpdater.abortMaintenanceUpdate()

    fun openGatt(device: BluetoothDevice) {
        gattLink.openGatt(device)
    }

    fun disconnect(clearSelection: Boolean, reason: String) {
        BeetLog.d(TAG) { "disconnect(clearSelection=$clearSelection, reason=$reason)" }
        gattLink.disconnectGatt(clearSelection, reason)
    }

    // --- BeetGattLinkDelegate (session-state decisions the GATT link asks the coordinator to make) ---

    override fun connectingDetail(deviceAddress: String): String =
        if (expectedControllerActionActive()) {
            expectedControllerActionConnectingDetail()
        } else {
            strings.get(R.string.runtime_connecting_to_controller, deviceAddress)
        }

    override fun negotiatingDetail(): String = expectedControllerActionConnectingDetail()

    override fun expectedActionActive(): Boolean = expectedControllerActionActive()

    override fun expectedRebootPending(): Boolean = expectedControllerAction == ExpectedControllerAction.Reboot

    override fun clearExpectedControllerAction() {
        expectedControllerAction = ExpectedControllerAction.None
        expectedControllerActionUntilMs = 0L
    }

    override fun resetSyncState() {
        BeetLog.d(TAG, "resetSyncState()")
        commandPump.resetChunkAssembler()
        gattLink.cancelControllerInfoRetry("reset sync state")
        host.resetSyncState()
    }

    override fun cancelEventSyncJob() {
        eventSyncJob?.cancel()
        eventSyncJob = null
    }

    override fun clearPendingMoistureTests() {
        runtimeCommands.clearPendingMoistureTests()
    }

    override fun applyUserCommandSideEffects(result: BeetCommandResult) {
        when {
            result.command == "reboot_controller" && result.status == "accepted" ->
                setExpectedControllerAction(ExpectedControllerAction.Reboot)
            result.command == "factory_reset" && result.status == "accepted" -> {
                host.removeLastAddress()
                clearCachedControllerHistory()
                setExpectedControllerAction(ExpectedControllerAction.FactoryReset)
            }
        }
    }

    override suspend fun <T> withSyncPausedForCommand(block: suspend () -> T): T =
        commandPump.withSyncPausedForCommand(block)

    private suspend fun awaitSyncResumeIfNeeded() {
        commandPump.awaitSyncResumeIfNeeded()
    }

    private suspend fun sendSyncCommand(payload: String): BeetCommandResult =
        commandPump.sendSyncCommand(payload)

    override suspend fun sendCommand(payload: String): BeetCommandResult =
        commandPump.sendCommand(payload)

    private fun startBackgroundEventSync(force: Boolean = false, limit: Int = MAX_BACKGROUND_EVENT_DOWNLOAD) {
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
            val cachedWatering = eventStore.loadWateringEvents(deviceId)
            val cachedSystem = eventStore.loadSystemEvents(deviceId)
            host.updateState {
                it.copy(
                    recentEvents = mergeWateringEvents(it.recentEvents, cachedWatering),
                    systemEvents = mergeSystemEvents(it.systemEvents, cachedSystem),
                    eventsLoading = true,
                    eventSync = BeetEventSyncState(active = true, phase = BeetEventSyncPhase.CatchingUp),
                )
            }

            val wateringSummary = runCatching { sendSyncCommand(BeetJsonCodec.getHistorySummary()) }.getOrNull()?.historySummary
            val systemSummary = runCatching { sendSyncCommand(BeetJsonCodec.getSystemHistorySummary()) }.getOrNull()?.systemHistorySummary
            host.updateState {
                it.copy(
                    historySummary = wateringSummary ?: it.historySummary,
                    systemHistorySummary = systemSummary ?: it.systemHistorySummary,
                )
            }

            /* Request high connection priority to reduce connection interval during bulk event sync */
            @Suppress("MissingPermission")
            val priorityRequested = host.session.currentGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) ?: false
            BeetLog.d(TAG) { "startBackgroundEventSync: requested CONNECTION_PRIORITY_HIGH success=$priorityRequested" }

            try {
                /*
                 * Coalesce event ingestion into the repository state in batches.
                 * Per-event state updates during a bulk backlog sync (thousands of events)
                 * cause a recomposition storm on the UI thread which can race with
                 * AndroidComposeView.draw (performMeasureAndLayout crash). SQLite caching
                 * stays per-event; only the state emission is batched.
                 */
                when (runBurstEventSync(wateringSummary, systemSummary)) {
                    BurstSyncOutcome.Completed, BurstSyncOutcome.Aborted -> Unit
                    BurstSyncOutcome.Legacy -> runLegacyBacklogSync(deviceId, wateringSummary, systemSummary, limit)
                }
            } finally {
                /* Restore balanced connection priority when event sync finishes or is cancelled */
                @Suppress("MissingPermission")
                host.session.currentGatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                BeetLog.d(TAG) { "startBackgroundEventSync: restored CONNECTION_PRIORITY_BALANCED" }
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


    private enum class BurstSyncOutcome { Completed, Legacy, Aborted }

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
    ): BurstSyncOutcome {
        resetBurstState()
        val deviceId = host.state.value.controllerInfo?.deviceId ?: return BurstSyncOutcome.Legacy

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
        var startWatering = maxOf(1L, eventStore.loadSyncWatermark(deviceId, BeetStreamKind.WATERING.wireName), oldestWatering)
        var startSystem = maxOf(1L, eventStore.loadSyncWatermark(deviceId, BeetStreamKind.SYSTEM.wireName), oldestSystem)
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
                val run = eventSyncEngine.streamEvents(
                    kind = kind,
                    fromSeq = cursor,
                    maxEvents = BURST_WINDOW_EVENTS,
                    isCancelled = { commandPump.syncPauseRequested || maintenanceUpdater.isUploadActive },
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
                        synchronized(burstLock) { flushBurstBuffersLocked() }
                        resetBurstState()
                        return BurstSyncOutcome.Legacy
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
                            eventStore.saveSyncWatermark(deviceId, kind.wireName, cursor - 1L)
                        }
                        val latest = if (kind == BeetStreamKind.WATERING) latestWatering else latestSystem
                        if (cursor > latest || attempts >= MAX_BURST_WINDOWS_PER_KIND) {
                            break
                        }
                        /* The controller pump ended this window with stream_end
                           complete; arm the next window to continue the backlog. */
                    }
                    is BeetStreamRun.Cancelled -> {
                        synchronized(burstLock) { flushBurstBuffersLocked() }
                        if (attempts >= MAX_BURST_ATTEMPTS_PER_KIND ||
                            host.state.value.connection.phase != BeetConnectionPhase.Connected ||
                            maintenanceUpdater.isUploadActive
                        ) {
                            resetBurstState()
                            return BurstSyncOutcome.Aborted
                        }
                        awaitSyncResumeIfNeeded()
                        cursor = run.nextCursor
                    }
                    is BeetStreamRun.Disconnected -> {
                        synchronized(burstLock) { flushBurstBuffersLocked() }
                        resetBurstState()
                        return BurstSyncOutcome.Aborted
                    }
                }
            }
            synchronized(burstLock) { flushBurstBuffersLocked() }
        }
        return BurstSyncOutcome.Completed
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
                runCatching { eventStore.saveWateringEvents(deviceId, watering) }
                runCatching { eventStore.saveSystemEvents(deviceId, system) }
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
            isPauseRequested = { commandPump.syncPauseRequested },
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
        val result = runCatching { sendSyncCommand(BeetJsonCodec.getEvent(sequence)) }.getOrNull()
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
        val result = runCatching { sendSyncCommand(BeetJsonCodec.getSystemEvent(sequence)) }.getOrNull()
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

    private fun mergeWateringEvents(current: List<BeetWateringEvent>, incoming: List<BeetWateringEvent>): List<BeetWateringEvent> =
        (current + incoming)
            .filter {
                it.bootId > 0L &&
                    (!it.timeValid || it.endedAtUnixSeconds >= ((System.currentTimeMillis() / 1000L) - EVENT_RETENTION_SECONDS))
            }
            .associateBy { it.sequenceNumber }
            .values
            .sortedByDescending { it.sequenceNumber }
            .let { if (it.size > EVENT_UI_MEMORY_CAP) it.take(EVENT_UI_MEMORY_CAP) else it }

    private fun mergeSystemEvents(current: List<BeetSystemEvent>, incoming: List<BeetSystemEvent>): List<BeetSystemEvent> =
        mergeRetainedSystemEvents(
            current = current,
            incoming = incoming,
            cutoffUnixSeconds = (System.currentTimeMillis() / 1000L) - EVENT_RETENTION_SECONDS,
        ).let { if (it.size > EVENT_UI_MEMORY_CAP) it.take(EVENT_UI_MEMORY_CAP) else it }

    private fun ingestWateringEvent(deviceId: String, event: BeetWateringEvent) {
        eventStore.saveWateringEvents(deviceId, listOf(event))
        host.updateState { state -> state.copy(recentEvents = mergeWateringEvents(state.recentEvents, listOf(event))) }
    }

    private fun ingestSystemEvent(deviceId: String, event: BeetSystemEvent) {
        eventStore.saveSystemEvents(deviceId, listOf(event))
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
        val result = runCatching { sendCommand(BeetJsonCodec.setTime(unixSeconds)) }.getOrNull() ?: return false
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

    private fun completeInitialSyncIfReady() {
        if (!host.session.tryCompleteInitialSync()) {
            return
        }
        gattLink.cancelConnectionTimeout()
        gattLink.cancelControllerInfoRetry("initial sync completed")
        host.persistLastAddress(host.currentAddress)
        clearExpectedControllerAction()
        BeetLog.d(TAG, "Initial sync started for session address=${host.currentAddress}")

        host.scope.launch {
            if (maintenanceUpdater.isUploadActive) {
                BeetLog.d(TAG, "Skipping post-sync refreshes because maintenance update is active")
                host.updateConnection(BeetConnectionPhase.Connected, strings.get(R.string.runtime_connected_to_controller))
                return@launch
            }

            // Load pair names and combined piggyback configuration FIRST before marking connected
            fetchPairNamesInternal()
            fetchPairCombinedInternal()

            if (host.session.currentGatt == null || host.state.value.connection.phase == BeetConnectionPhase.Disconnected) {
                return@launch
            }

            BeetLog.d(TAG, "Initial sync completed for session address=${host.currentAddress}")
            host.updateConnection(BeetConnectionPhase.Connected, strings.get(R.string.runtime_connected_to_controller))

            gattLink.negotiateHighSpeedLink()

            refreshValveConfig()
            refreshWateringInterval()
            refreshMaxActivePumps()

            delay(POST_CONNECT_EVENT_SYNC_DELAY_MS)
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) return@launch
            startBackgroundEventSync()
        }
    }

    override fun handleControllerInfo(payload: ByteArray) {
        val info = try {
            BeetJsonCodec.parseControllerInfo(payload.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            BeetLog.e(TAG, "Controller info payload parse failed", error)
            disconnectGatt(clearSelection = false, reason = "invalid controller info payload")
            host.clearSession()
            host.updateConnection(BeetConnectionPhase.Error, strings.get(R.string.runtime_controller_info_invalid))
            return
        }
        if (info.protocolVersion != BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION) {
            BeetLog.e(
                TAG,
                "Unsupported protocol version ${info.protocolVersion}, expected=${BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION}",
            )
            disconnectGatt(clearSelection = false, reason = "unsupported protocol version ${info.protocolVersion}")
            host.clearSession()
            host.updateConnection(
                BeetConnectionPhase.Error,
                strings.get(
                    R.string.runtime_unsupported_protocol,
                    info.protocolVersion,
                    BuildConfig.BEET_RUNTIME_PROTOCOL_VERSION,
                ),
            )
            return
        }
        host.session.controllerInfoReadAttempts = 0
        gattLink.cancelControllerInfoRetry("controller info read succeeded")
        host.session.markControllerInfoLoaded(info.pairCount)
        BeetLog.d(TAG) { "handleControllerInfo(deviceId=${info.deviceId}, protocol=${info.protocolVersion}, pairCount=${info.pairCount})" }
        host.updateState { it.copy(controllerInfo = info) }
        completeInitialSyncIfReady()
    }

    override fun hasCompleteRuntimeService(): Boolean = gattLink.hasCompleteRuntimeService()

    override fun readControllerInfo(gatt: BluetoothGatt): Boolean = gattLink.readControllerInfo(gatt)

    override fun handleStatePayload(payload: ByteArray) {
        val json = payload.toString(StandardCharsets.UTF_8)
        val message = try {
            BeetJsonCodec.parseStateMessage(json)
        } catch (error: Exception) {
            BeetLog.e(TAG, "State payload parse failed: $json", error)
            null
        }
        when (message) {
            is BeetStateMessage.DeviceStateUpdate -> {
                val deviceState = message.data
                host.session.initialDeviceFrameReceived = true
                if (deviceState.timeValid) {
                    host.session.syncedTimeBootId = deviceState.bootId
                }
                BeetLog.d(TAG) { "handleStatePayload(deviceFrame battery=${deviceState.batteryMillivolts} activePumps=${deviceState.activePumps})" }
                host.updateState { state ->
                    state.copy(
                        deviceState = deviceState,
                        connectedAtMillis = if (state.connectedAtMillis == 0L) System.currentTimeMillis() else state.connectedAtMillis,
                        connectedAtControllerUptimeSeconds = if (state.connectedAtControllerUptimeSeconds == 0L) deviceState.uptimeSeconds else state.connectedAtControllerUptimeSeconds,
                    )
                }
                completeInitialSyncIfReady()
            }
            is BeetStateMessage.PairStateUpdate -> {
                val pairState = message.data
                val syncedPairs = host.session.markPairSynced(pairState.pairIndex)
                BeetLog.d(TAG) { "handleStatePayload(pairFrame pair=${pairState.pairIndex} state=${pairState.state} syncedPairs=$syncedPairs)" }
                host.updateState { state ->
                    state.copy(
                        pairStates = state.pairStates + (pairState.pairIndex to pairState),
                    )
                }
                runtimeCommands.onPairStateForMoistureTest(pairState)
                completeInitialSyncIfReady()
            }
            null -> {
                BeetLog.w(TAG, "Ignoring unknown state payload: $json")
            }
            is BeetStateMessage.SystemEventUpdate -> {
                val deviceId = host.state.value.controllerInfo?.deviceId
                val burstConsumed = streamRouter.onEventFrame(BeetStreamKind.SYSTEM)
                if (burstConsumed) {
                    ingestBurstSystemEvent(message.data)
                } else if (deviceId != null) {
                    ingestSystemEvent(deviceId, message.data)
                } else {
                    host.updateState { it.copy(systemEvents = mergeSystemEvents(it.systemEvents, listOf(message.data))) }
                }
            }

            is BeetStateMessage.WateringEventUpdate -> {
                streamRouter.onEventFrame(BeetStreamKind.WATERING)
                ingestBurstWateringEvent(message.data)
            }

            is BeetStateMessage.StreamEndUpdate -> {
                streamRouter.onStreamEnd(message.data)
            }
        }
    }

    private fun ingestBurstWateringEvent(event: BeetWateringEvent) {
        synchronized(burstLock) {
            burstWateringBuffer += event
            if (burstWateringBuffer.size >= EVENT_UI_BATCH_SIZE && burstUiFlushAllowedLocked()) flushBurstBuffersLocked()
        }
    }

    private fun ingestBurstSystemEvent(event: BeetSystemEvent) {
        synchronized(burstLock) {
            burstSystemBuffer += event
            if (burstSystemBuffer.size >= EVENT_UI_BATCH_SIZE && burstUiFlushAllowedLocked()) flushBurstBuffersLocked()
        }
    }

    /* Caller must hold [burstLock]. */
    private fun burstUiFlushAllowedLocked(): Boolean =
        SystemClock.elapsedRealtime() - lastBurstUiFlushElapsedMs >= BURST_UI_FLUSH_MIN_INTERVAL_MS
    private fun flushBurstBuffersLocked() {
        if (burstWateringBuffer.isEmpty() && burstSystemBuffer.isEmpty()) return
        val watering = burstWateringBuffer.toList()
        val system = burstSystemBuffer.toList()
        burstWateringBuffer.clear()
        burstSystemBuffer.clear()
        lastBurstUiFlushElapsedMs = SystemClock.elapsedRealtime()
        val deviceId = host.state.value.controllerInfo?.deviceId
        if (deviceId != null) {
            runCatching { eventStore.saveWateringEvents(deviceId, watering) }
            runCatching { eventStore.saveSystemEvents(deviceId, system) }
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
        streamRouter.abort(BeetStreamKind.WATERING)
        streamRouter.abort(BeetStreamKind.SYSTEM)
    }

    override fun handleCommandPayload(payload: ByteArray) {
        val result = commandPump.decodeCommandPayload(payload) ?: return
        result.calibration?.let { calibration ->
            host.updateState { state -> state.copy(calibrations = state.calibrations + (calibration.pairIndex to calibration)) }
        }
        result.historySummary?.let { summary ->
            host.updateState { it.copy(historySummary = summary) }
        }
        result.event?.let { event ->
            val deviceId = host.state.value.controllerInfo?.deviceId
            if (deviceId != null) {
                ingestWateringEvent(deviceId, event)
            } else {
                host.updateState { state -> state.copy(recentEvents = mergeWateringEvents(state.recentEvents, listOf(event))) }
            }
        }
        result.systemHistorySummary?.let { summary ->
            host.updateState { it.copy(systemHistorySummary = summary) }
        }
        result.systemEvent?.let { event ->
            val deviceId = host.state.value.controllerInfo?.deviceId
            if (deviceId != null) {
                ingestSystemEvent(deviceId, event)
            } else {
                host.updateState { state -> state.copy(systemEvents = mergeSystemEvents(state.systemEvents, listOf(event))) }
            }
        }
        result.valveConfig?.let { config ->
            host.updateState { it.copy(valveConfig = config) }
        }
        result.wateringInterval?.let { interval ->
            host.updateState { it.copy(wateringInterval = interval) }
        }
        result.pairWiring?.let { wiring ->
            host.updateState { state ->
                state.copy(
                    pairWirings = state.pairWirings + (wiring.pairIndex to wiring),
                    pairWiringLoading = state.pairWiringLoading - wiring.pairIndex,
                    pairWiringErrors = state.pairWiringErrors - wiring.pairIndex,
                )
            }
        }
        result.maxActivePumps?.let { maxPumps ->
            host.updateState { it.copy(maxActivePumps = maxPumps.maxActivePumps) }
        }
        result.pairNames?.let { names ->
            val namesMap = names.names.mapIndexed { index, name ->
                (index + 1) to name
            }.toMap()
            host.updateState { it.copy(pairNames = namesMap) }
        }
        commandPump.completePendingResult(result)
    }

    override fun disconnectGatt(clearSelection: Boolean, reason: String) {
        gattLink.disconnectGatt(clearSelection, reason)
    }

    private fun messageForResult(result: BeetCommandResult): String = commandMessageForResult(result, strings)

    private fun expectedControllerActionConnectingDetail(): String = when (expectedControllerAction) {
        ExpectedControllerAction.Reboot -> strings.get(R.string.runtime_reboot_reconnecting)
        ExpectedControllerAction.FactoryReset -> strings.get(R.string.runtime_factory_reset_waiting)
        ExpectedControllerAction.None -> strings.get(R.string.runtime_negotiating_ble_session)
    }

    override fun handleExpectedControllerActionDisconnect() {
        when (expectedControllerAction) {
            ExpectedControllerAction.Reboot -> {
                val reconnectDevice = maintenanceUpdater.resolveReconnectDevice()
                if (!expectedControllerActionActive() || reconnectDevice == null) {
                    clearExpectedControllerAction()
                    host.clearSession()
                    host.requestStartScan(detail = strings.get(R.string.runtime_reboot_reconnect_failed))
                    return
                }
                host.updateConnection(BeetConnectionPhase.Connecting, strings.get(R.string.runtime_reboot_reconnecting))
                host.clearSession()
                host.scope.launch {
                    delay(EXPECTED_REBOOT_RECONNECT_DELAY_MS)
                    if (expectedControllerAction != ExpectedControllerAction.Reboot || host.session.currentGatt != null) {
                        return@launch
                    }
                    host.requestOpenGatt(reconnectDevice)
                }
            }
            ExpectedControllerAction.FactoryReset -> {
                clearExpectedControllerAction()
                host.currentAddress = null
                host.clearSession()
                host.updateState { it.copy(selectedAddress = null) }
                host.requestStartScan(detail = strings.get(R.string.runtime_factory_reset_complete), clearResults = true)
            }
            ExpectedControllerAction.None -> {}
        }
    }

    companion object {
        private const val TAG = "BeetGattSession"
        private const val MAX_BURST_ATTEMPTS_PER_KIND = 4
        private const val MAINTENANCE_PROGRESS_GAP_RESET_MS = 10_000L
        private const val MAX_BACKGROUND_EVENT_DOWNLOAD = 120
        /* Burst events per stream_events window. Large windows amortize the ack/end
           command round trips; the pump stays cancelable and resumable either way. */
        private const val BURST_WINDOW_EVENTS = 5000L
        /* Safety cap on windows per kind (5000 * 40 covers both full rings). */
        private const val MAX_BURST_WINDOWS_PER_KIND = 40
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
        private const val EXPECTED_CONTROLLER_ACTION_TIMEOUT_MS = 30_000L
        private const val EXPECTED_REBOOT_RECONNECT_DELAY_MS = 1_000L
        private const val POST_CONNECT_EVENT_SYNC_DELAY_MS = 3_000L
    }

    private class MaintenanceAbortRequestedException : IllegalStateException("Maintenance update aborted")

}
