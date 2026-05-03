package de.aarondietz.beetmeister.beet.model.connection

data class BeetConnectionState(
    val phase: BeetConnectionPhase = BeetConnectionPhase.Idle,
    val detail: String? = null,
)
