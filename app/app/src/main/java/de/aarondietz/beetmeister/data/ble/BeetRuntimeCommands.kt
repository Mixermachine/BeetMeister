package de.aarondietz.beetmeister.data.ble

import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.data.protocol.BeetJsonCodec
import de.aarondietz.beetmeister.data.repository.BeetRepositoryCallbacks
import de.aarondietz.beetmeister.data.repository.commandMessageForResult
import de.aarondietz.beetmeister.logging.BeetLog
import de.aarondietz.beetmeister.model.command.BeetCommandResult
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetPairCombined
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.controller.TargetMoistureLevel
import de.aarondietz.beetmeister.model.repository.displayedPairCount
import de.aarondietz.beetmeister.model.repository.isFollower
import de.aarondietz.beetmeister.model.repository.isLead
import de.aarondietz.beetmeister.model.repository.leadFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Narrow view back into the GATT session that the runtime command facade needs.
 * Implemented by BeetGattSessionCoordinator.
 */
internal interface BeetRuntimeCommandLink {
    suspend fun sendCommand(payload: String): BeetCommandResult
    suspend fun <T> withSyncPausedForCommand(block: suspend () -> T): T
    /** Reboot/factory-reset expected-action wiring lives with the session state machine. */
    fun applyUserCommandSideEffects(result: BeetCommandResult)
}

/**
 * Runtime command facade extracted from BeetGattSessionCoordinator (cluster 2 of the
 * coordinator split plan): thin wrappers that serialize user/runtime commands over the
 * session's command pipeline. Pure relocation - no protocol or ordering changes.
 *
 * Runtime protocol surface (combined-pairs followers-mask semantics included) is preserved
 * verbatim; any wire-behavior change here still requires a runtime protocol version bump.
 */
internal class BeetRuntimeCommands(
    private val host: BeetRepositoryCallbacks,
    private val link: BeetRuntimeCommandLink,
) {
    private val strings get() = host.strings

    private val pendingMoistureTests = mutableMapOf<Int, Boolean>()
    private val pendingPairWiringLoads = mutableSetOf<Int>()

    /** Session cleared (disconnect): drop in-flight moisture test tracking. */
    fun clearPendingMoistureTests() {
        pendingMoistureTests.clear()
    }

    fun sendRawCommand(payload: String): BeetCommandResult {
        return kotlinx.coroutines.runBlocking {
            link.withSyncPausedForCommand { link.sendCommand(payload) }
        }
    }

    fun refreshCalibrations() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            host.updateState { state -> state.copy(calibrationsRefreshing = true) }
            try {
                link.withSyncPausedForCommand {
                    for (pairIndex in 1..8) {
                        runCatching { link.sendCommand(BeetJsonCodec.getCalibration(pairIndex)) }
                            .onFailure {
                                host.setCommandMessage(strings.get(R.string.runtime_calibration_refresh_failed, pairIndex))
                            }
                            .onSuccess { result ->
                                if (result.status != "accepted") {
                                    host.setCommandMessage(strings.get(R.string.runtime_calibration_refresh_failed, pairIndex))
                                }
                            }
                    }
                }
            } finally {
                host.updateState { state -> state.copy(calibrationsRefreshing = false) }
            }
        }
    }

    fun manualStart(pairIndex: Int, durationSeconds: Int?) {
        host.scope.launch {
            if (durationSeconds != null && durationSeconds !in 1..MAX_MANUAL_DURATION_SECONDS) {
                host.setCommandMessage(strings.get(R.string.runtime_manual_duration_invalid))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.manualStart(pairIndex, durationSeconds))
        }
    }

    fun manualStop(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.manualStop(pairIndex)) }
    }

    fun moistureTestStart(pairIndex: Int) {
        host.scope.launch {
            pendingMoistureTests[pairIndex] = false
            runCatching {
                link.withSyncPausedForCommand { link.sendCommand(BeetJsonCodec.moistureTestStart(pairIndex)) }
            }
                .onSuccess { result ->
                    if (result.status != "accepted") {
                        pendingMoistureTests.remove(pairIndex)
                    }
                    host.setCommandMessage(messageForResult(result))
                }
                .onFailure { error ->
                    pendingMoistureTests.remove(pairIndex)
                    host.setCommandMessage(error.message ?: strings.get(R.string.runtime_command_failed))
                }
        }
    }

    fun clearPairError(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.resetBlock(pairIndex)) }
    }

    fun resetBlock(pairIndex: Int) = clearPairError(pairIndex)

    fun disablePair(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.disablePair(pairIndex)) }
    }

    fun enablePair(pairIndex: Int) {
        host.scope.launch { sendUserCommand(BeetJsonCodec.enablePair(pairIndex)) }
    }

    fun saveCalibration(pairIndex: Int, dryMillivolts: Int, wetMillivolts: Int) {
        host.scope.launch {
            if (dryMillivolts <= wetMillivolts || dryMillivolts == 0 || wetMillivolts == 0) {
                host.setCommandMessage(strings.get(R.string.runtime_calibration_invalid_order))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeCalibration(pairIndex, dryMillivolts, wetMillivolts))
            refreshCalibrations()
        }
    }

    fun loadPairWiring(pairIndex: Int) {
        host.scope.launch {
            if (!beetIsValidPairIndex(pairIndex)) {
                return@launch
            }
            val alreadyLoaded = host.state.value.pairWirings.containsKey(pairIndex)
            val alreadyLoading = synchronized(pendingPairWiringLoads) { !pendingPairWiringLoads.add(pairIndex) }
            if (alreadyLoaded || alreadyLoading) {
                return@launch
            }
            host.updateState { state ->
                state.copy(
                    pairWiringLoading = state.pairWiringLoading + pairIndex,
                    pairWiringErrors = state.pairWiringErrors - pairIndex,
                )
            }
            try {
                val result = link.withSyncPausedForCommand { link.sendCommand(BeetJsonCodec.getPairWiring(pairIndex)) }
                if (result.status == "accepted" && result.pairWiring != null) {
                    host.updateState { state ->
                        state.copy(
                            pairWiringLoading = state.pairWiringLoading - pairIndex,
                            pairWiringErrors = state.pairWiringErrors - pairIndex,
                        )
                    }
                } else {
                    host.updateState { state ->
                        state.copy(
                            pairWiringLoading = state.pairWiringLoading - pairIndex,
                            pairWiringErrors = state.pairWiringErrors + (pairIndex to commandMessageForResult(result, strings)),
                        )
                    }
                }
            } catch (error: Exception) {
                host.updateState { state ->
                    state.copy(
                        pairWiringLoading = state.pairWiringLoading - pairIndex,
                        pairWiringErrors = state.pairWiringErrors + (pairIndex to (error.message ?: strings.get(R.string.runtime_command_failed))),
                    )
                }
            } finally {
                synchronized(pendingPairWiringLoads) {
                    pendingPairWiringLoads.remove(pairIndex)
                }
            }
        }
    }

    suspend fun fetchPairNamesInternal() {
        try {
            val result = link.withSyncPausedForCommand { link.sendCommand(BeetJsonCodec.getPairNames()) }
            result.pairNames?.let { names ->
                val namesMap = names.names.mapIndexed { index, name ->
                    (index + 1) to name
                }.toMap()
                host.updateState { it.copy(pairNames = namesMap) }
            }
        } catch (_: Exception) {
            // names are optional - if the command fails (e.g. unsupported on old firmware),
            // the UI falls back to "Pair N"
        }
    }

    fun loadPairNames() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            fetchPairNamesInternal()
        }
    }

    fun storePairName(pairIndex: Int, name: String) {
        host.scope.launch {
            if (!beetIsValidPairIndex(pairIndex)) {
                return@launch
            }
            if (name.length > BEET_PAIR_NAME_MAX_LEN) {
                return@launch
            }
            // Optimistic update
            host.updateState { it.copy(pairNames = it.pairNames + (pairIndex to name)) }
            try {
                val result = link.withSyncPausedForCommand {
                    link.sendCommand(BeetJsonCodec.storePairName(pairIndex, name))
                }
                if (result.status != "accepted") {
                    // Re-fetch authoritative state
                    loadPairNames()
                }
            } catch (_: Exception) {
                loadPairNames()
            }
        }
    }

    suspend fun fetchPairCombinedInternal() {
        try {
            link.withSyncPausedForCommand {
                val pairCount = host.state.value.displayedPairCount
                val combinedMap = mutableMapOf<Int, BeetPairCombined>()
                for (pairIndex in 1..pairCount) {
                    val result = runCatching {
                        link.sendCommand(BeetJsonCodec.getPairCombined(pairIndex))
                    }.getOrNull()
                    if (result?.status == "accepted" && result.pairCombined != null) {
                        combinedMap[pairIndex] = result.pairCombined
                    }
                }
                host.updateState { state ->
                    state.copy(
                        pairCombined = state.pairCombined + combinedMap,
                        isPairCombinedLoaded = true,
                    )
                }
            }
        } catch (_: Exception) {
            host.updateState { it.copy(isPairCombinedLoaded = true) }
        }
    }

    fun refreshPairCombined() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            fetchPairCombinedInternal()
        }
    }

    fun setPairSensorSource(pairIndex: Int, leadPairIndex: Int?) {
        BeetLog.i(TAG, "setPairSensorSource pair=$pairIndex lead=$leadPairIndex")
        host.scope.launch {
            if (!beetIsValidPairIndex(pairIndex)) {
                return@launch
            }
            if (!host.state.value.isPairCombinedLoaded) {
                BeetLog.w(TAG, "Cannot set sensor source: pair combined configuration is not loaded yet")
                return@launch
            }
            val currentState = host.state.value
            val currentLead = currentState.leadFor(pairIndex)
            if (currentLead == leadPairIndex) {
                return@launch
            }

            // Cannot set a sensor source if pairIndex is currently a lead for other pairs
            if (leadPairIndex != null && currentState.isLead(pairIndex)) {
                BeetLog.w(TAG, "Cannot set sensor source for lead pair $pairIndex without clearing followers first")
                return@launch
            }

            // Cannot choose a pair that is itself a follower
            if (leadPairIndex != null && currentState.isFollower(leadPairIndex)) {
                BeetLog.w(TAG, "Cannot set follower pair $leadPairIndex as lead for pair $pairIndex")
                return@launch
            }

            // 1. If currently following an existing lead, remove from that lead's followers mask
            if (currentLead != null) {
                // Fetch fresh lead combined state from controller to prevent stale in-memory masks
                val oldLeadFromController = runCatching {
                    link.withSyncPausedForCommand {
                        link.sendCommand(BeetJsonCodec.getPairCombined(currentLead))
                    }
                }.getOrNull()?.takeIf { it.status == "accepted" }?.pairCombined

                val currentOldMask = oldLeadFromController?.followersMask
                    ?: currentState.pairCombined[currentLead]?.followersMask
                    ?: 0
                val updatedOldMask = currentOldMask and (1 shl (pairIndex - 1)).inv()
                // Optimistic update
                host.updateState { state ->
                    state.copy(
                        pairCombined = state.pairCombined + (currentLead to BeetPairCombined(currentLead, updatedOldMask)),
                    )
                }
                try {
                    val result = link.withSyncPausedForCommand {
                        link.sendCommand(BeetJsonCodec.storePairCombined(currentLead, updatedOldMask))
                    }
                    if (result.status != "accepted") {
                        host.scope.launch { beetLoadPairCombinedWithRetry(currentLead) }
                    }
                } catch (_: Exception) {
                    BeetLog.w(TAG, "setPairSensorSource store failed lead=$currentLead")
                    beetLoadPairCombinedWithRetry(currentLead)
                }
            }

            // 2. If setting a new lead, add to that lead's followers mask
            if (leadPairIndex != null && beetIsValidPairIndex(leadPairIndex) && leadPairIndex != pairIndex) {
                // Fetch fresh lead combined state from controller to prevent stale in-memory masks
                val leadFromController = runCatching {
                    link.withSyncPausedForCommand {
                        link.sendCommand(BeetJsonCodec.getPairCombined(leadPairIndex))
                    }
                }.getOrNull()?.takeIf { it.status == "accepted" }?.pairCombined

                val currentNewMask = leadFromController?.followersMask
                    ?: host.state.value.pairCombined[leadPairIndex]?.followersMask
                    ?: 0
                val updatedNewMask = currentNewMask or (1 shl (pairIndex - 1))
                // Optimistic update
                host.updateState { state ->
                    val combinedObj = BeetPairCombined(leadPairIndex, updatedNewMask)
                    state.copy(
                        pairCombined = state.pairCombined + (leadPairIndex to combinedObj),
                    )
                }
                try {
                    val result = link.withSyncPausedForCommand {
                        link.sendCommand(BeetJsonCodec.storePairCombined(leadPairIndex, updatedNewMask))
                    }
                    if (result.status == "accepted" && result.pairCombined != null) {
                        host.updateState { state ->
                            state.copy(
                                pairCombined = state.pairCombined + (leadPairIndex to result.pairCombined),
                            )
                        }
                    } else {
                        beetLoadPairCombinedWithRetry(leadPairIndex)
                    }
                } catch (_: Exception) {
                    beetLoadPairCombinedWithRetry(leadPairIndex)
                }
            }
        }
    }

    fun loadPairCombined(pairIndex: Int) {
        host.scope.launch {
            if (!beetIsValidPairIndex(pairIndex)) {
                return@launch
            }
            // Skip when the initial sync already fetched this pair's combined
            // config. The per-detail-screen entry otherwise adds another REAL
            // lane command to the same rate-limiter second, which can push the
            // subsequent store_pair_combined past the 4-command/s limit and
            // silently revert the user's selection.
            if (host.state.value.isPairCombinedLoaded &&
                host.state.value.pairCombined.containsKey(pairIndex)
            ) {
                return@launch
            }
            beetLoadPairCombinedWithRetry(pairIndex)
        }
    }

    private suspend fun beetLoadPairCombinedWithRetry(pairIndex: Int) {
        // The controller rate-limits REAL-lane commands to 4 per 1 s window.
        // Retry through window boundaries so a rejection from a burst (e.g. a
        // detail screen opening) converges instead of leaving the optimistic
        // in-memory state diverged from the controller.
        repeat(3) { attempt ->
            val result = runCatching {
                link.withSyncPausedForCommand {
                    link.sendCommand(BeetJsonCodec.getPairCombined(pairIndex))
                }
            }.getOrNull()
            if (result != null && result.status == "accepted" && result.pairCombined != null) {
                host.updateState { state ->
                    state.copy(
                        pairCombined = state.pairCombined + (pairIndex to result.pairCombined),
                    )
                }
                return
            }
            if (attempt < 2) {
                delay(1100L)
            }
        }
    }

    fun storePairCombined(pairIndex: Int, followersMask: Int) {
        host.scope.launch {
            if (!beetIsValidPairIndex(pairIndex)) {
                return@launch
            }
            try {
                val result = link.withSyncPausedForCommand {
                    link.sendCommand(BeetJsonCodec.storePairCombined(pairIndex, followersMask))
                }
                if (result.status == "accepted" && result.pairCombined != null) {
                    host.updateState { state ->
                        state.copy(
                            pairCombined = state.pairCombined + (pairIndex to result.pairCombined),
                        )
                    }
                }
            } catch (_: Exception) {
                // re-fetch on failure
                loadPairCombined(pairIndex)
            }
        }
    }

    fun loadPairConfig(pairIndex: Int) {
        host.scope.launch {
            if (!beetIsValidPairIndex(pairIndex)) {
                return@launch
            }
            // Skip when already loaded (mirror of loadPairWiring dedupe) to keep
            // detail-screen entry inside the controller's REAL-lane rate limit.
            if (host.state.value.pairConfigs.containsKey(pairIndex)) {
                return@launch
            }
            try {
                val result = link.withSyncPausedForCommand {
                    link.sendCommand(BeetJsonCodec.getPairConfig(pairIndex))
                }
                if (result.status == "accepted" && result.pairConfig != null) {
                    host.updateState { state ->
                        state.copy(
                            pairConfigs = state.pairConfigs + (pairIndex to result.pairConfig),
                        )
                    }
                }
            } catch (_: Exception) {
                // pair config is optional
            }
        }
    }

    fun storePairConfig(pairIndex: Int, targetLevel: TargetMoistureLevel, durationMultiplier: Int) {
        host.scope.launch {
            if (!beetIsValidPairIndex(pairIndex)) {
                return@launch
            }
            try {
                val result = link.withSyncPausedForCommand {
                    link.sendCommand(BeetJsonCodec.storePairConfig(pairIndex, targetLevel, durationMultiplier))
                }
                if (result.status == "accepted" && result.pairConfig != null) {
                    host.updateState { state ->
                        state.copy(
                            pairConfigs = state.pairConfigs + (pairIndex to result.pairConfig),
                        )
                    }
                }
            } catch (_: Exception) {
                loadPairConfig(pairIndex)
            }
        }
    }

    fun refreshValveConfig() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            host.updateState { state -> state.copy(valveConfigRefreshing = true) }
            try {
                runCatching { link.withSyncPausedForCommand { link.sendCommand(BeetJsonCodec.getValveConfig()) } }
            } finally {
                host.updateState { state -> state.copy(valveConfigRefreshing = false) }
            }
        }
    }

    fun refreshWateringInterval() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            host.updateState { state -> state.copy(wateringIntervalRefreshing = true) }
            try {
                runCatching { link.withSyncPausedForCommand { link.sendCommand(BeetJsonCodec.getWateringInterval()) } }
            } finally {
                host.updateState { state -> state.copy(wateringIntervalRefreshing = false) }
            }
        }
    }

    fun saveValveConfig(config: BeetValveConfig) {
        host.scope.launch {
            if (
                config.servoMinPulseMicros !in 500..2500 ||
                config.servoMaxPulseMicros !in 500..2500 ||
                config.servoMinPulseMicros >= config.servoMaxPulseMicros ||
                config.openPulseMicros !in config.servoMinPulseMicros..config.servoMaxPulseMicros ||
                config.shutPulseMicros !in config.servoMinPulseMicros..config.servoMaxPulseMicros ||
                config.moveDurationMillis !in 100..5000 ||
                config.settleDelayMillis !in 0..5000 ||
                config.openHoldMillis !in 0..10000
            ) {
                host.setCommandMessage(strings.get(R.string.runtime_valve_config_invalid))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeValveConfig(config))
        }
    }

    fun saveWateringInterval(seconds: Int) {
        host.scope.launch {
            if (seconds !in 300..86400) {
                host.setCommandMessage(strings.get(R.string.runtime_watering_interval_invalid))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeWateringInterval(seconds))
        }
    }

    fun refreshMaxActivePumps() {
        host.scope.launch {
            if (host.state.value.connection.phase != BeetConnectionPhase.Connected) {
                return@launch
            }
            runCatching { link.withSyncPausedForCommand { link.sendCommand(BeetJsonCodec.getMaxActivePumps()) } }
        }
    }

    fun storeMaxActivePumps(max: Int) {
        host.scope.launch {
            if (max !in 1..8) {
                host.setCommandMessage(strings.get(R.string.runtime_max_active_pumps_invalid))
                return@launch
            }
            sendUserCommand(BeetJsonCodec.storeMaxActivePumps(max))
        }
    }

    fun previewValvePosition(pulseMicros: Int) {
        host.scope.launch {
            runCatching { link.withSyncPausedForCommand { link.sendCommand(BeetJsonCodec.previewValvePosition(pulseMicros)) } }
                .onFailure { error -> host.setCommandMessage(error.message ?: strings.get(R.string.runtime_command_failed)) }
        }
    }

    fun openValve() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.openValve()) }
    }

    fun closeValve() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.closeValve()) }
    }

    fun rebootController() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.rebootController()) }
    }

    fun factoryResetController() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.factoryResetController()) }
    }

    fun runScheduler() {
        host.scope.launch { sendUserCommand(BeetJsonCodec.runScheduler()) }
    }

    /** Moisture-test result observation from pair state notifications (called by the coordinator). */
    fun onPairStateForMoistureTest(pairState: BeetPairState) {
        val hasSeenActiveState = pendingMoistureTests[pairState.pairIndex] ?: return
        when {
            pairState.state == "MOISTURE_TEST" -> {
                pendingMoistureTests[pairState.pairIndex] = true
            }
            hasSeenActiveState && pairState.state == "IDLE" -> {
                pendingMoistureTests.remove(pairState.pairIndex)
                host.setCommandMessage(strings.get(R.string.runtime_moisture_test_passed, pairState.pairIndex))
            }
            hasSeenActiveState && (pairState.blocked || pairState.state == "FAULT") -> {
                pendingMoistureTests.remove(pairState.pairIndex)
                host.setCommandMessage(
                    strings.get(
                        R.string.runtime_moisture_test_failed,
                        pairState.pairIndex,
                        pairBlockReasonLabel(pairState.blockReason),
                    ),
                )
            }
        }
    }

    private suspend fun sendUserCommand(payload: String) {
        runCatching { link.withSyncPausedForCommand { link.sendCommand(payload) } }
            .onSuccess { result ->
                link.applyUserCommandSideEffects(result)
                host.setCommandMessage(messageForResult(result))
            }
            .onFailure { error -> host.setCommandMessage(error.message ?: strings.get(R.string.runtime_command_failed)) }
    }

    private fun messageForResult(result: BeetCommandResult): String = commandMessageForResult(result, strings)

    private fun pairBlockReasonLabel(code: String): String = when (code) {
        "NONE" -> strings.get(R.string.block_reason_code_none)
        "MOISTURE_RESPONSE_TEST_FAILED" -> strings.get(R.string.block_reason_code_moisture_response_test_failed)
        "SENSOR_READING_INVALID" -> strings.get(R.string.block_reason_code_sensor_reading_invalid)
        "LOW_BATTERY_ABORT" -> strings.get(R.string.block_reason_code_low_battery_abort)
        else -> strings.get(R.string.common_unknown_with_code, code)
    }

    private fun beetIsValidPairIndex(pairIndex: Int): Boolean = pairIndex in 1..8

    companion object {
        // Same tag as the coordinator so log filtering is unchanged by the extraction.
        private const val TAG = "BeetGattSession"
        private const val BEET_PAIR_NAME_MAX_LEN = 15
        private const val MAX_MANUAL_DURATION_SECONDS = 1200
    }
}
