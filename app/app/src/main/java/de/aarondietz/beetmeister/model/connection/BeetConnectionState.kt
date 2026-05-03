package de.aarondietz.beetmeister.model.connection

data class BeetConnectionState(
    val phase: BeetConnectionPhase = BeetConnectionPhase.Idle,
    val detail: String? = null,
)
