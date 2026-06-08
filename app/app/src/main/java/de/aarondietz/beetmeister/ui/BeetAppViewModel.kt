package de.aarondietz.beetmeister.ui

import de.aarondietz.beetmeister.data.repository.BeetRepository
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.ViewModel

internal class BeetAppViewModel(
    private val repository: BeetRepository,
) : ViewModel() {
    val state: StateFlow<BeetRepositoryState> = repository.state

    init {
        repository.start()
    }

    fun refreshEnvironment() = repository.refreshEnvironment()

    fun startScan() = repository.startScan()

    fun connect(address: String) = repository.connect(address)

    fun disconnect() = repository.disconnect()

    fun clearMessage() = repository.clearCommandMessage()

    fun refreshCalibrations() = repository.refreshCalibrations()

    fun refreshHistorySummary() = repository.refreshHistorySummary()

    fun loadRecentEvents(limit: Int = 50) = repository.loadRecentEvents(limit)

    fun manualStart(pairIndex: Int, durationSeconds: Int?) = repository.manualStart(pairIndex, durationSeconds)

    fun manualStop(pairIndex: Int) = repository.manualStop(pairIndex)

    fun moistureTestStart(pairIndex: Int) = repository.moistureTestStart(pairIndex)

    fun clearPairError(pairIndex: Int) = repository.clearPairError(pairIndex)

    fun resetBlock(pairIndex: Int) = clearPairError(pairIndex)

    fun disablePair(pairIndex: Int) = repository.disablePair(pairIndex)

    fun enablePair(pairIndex: Int) = repository.enablePair(pairIndex)

    fun togglePairEnabled(pairIndex: Int) {
        val pairState = state.value.pairStates.firstOrNull { it.pairIndex == pairIndex } ?: return
        if (pairState.enabled) {
            repository.disablePair(pairIndex)
        } else {
            repository.enablePair(pairIndex)
        }
    }

    fun saveCalibration(pairIndex: Int, dryMillivolts: Int, wetMillivolts: Int) =
        repository.saveCalibration(pairIndex, dryMillivolts, wetMillivolts)

    fun refreshValveConfig() = repository.refreshValveConfig()

    fun saveValveConfig(config: BeetValveConfig) = repository.saveValveConfig(config)

    fun previewValvePosition(pulseMicros: Int) = repository.previewValvePosition(pulseMicros)

    fun openValve() = repository.openValve()

    fun closeValve() = repository.closeValve()

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
