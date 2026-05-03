package de.aarondietz.beetmeister.beet.model.stream

import de.aarondietz.beetmeister.beet.model.controller.BeetDeviceState
import de.aarondietz.beetmeister.beet.model.controller.BeetPairState
import de.aarondietz.beetmeister.beet.model.event.BeetSystemEvent

sealed interface BeetStateMessage {
    data class DeviceStateUpdate(val data: BeetDeviceState) : BeetStateMessage

    data class PairStateUpdate(val data: BeetPairState) : BeetStateMessage

    data class SystemEventUpdate(val data: BeetSystemEvent) : BeetStateMessage
}
