package de.aarondietz.beetmeister.model.stream

data class BeetEventSyncState(
    val active: Boolean = false,
    val transferred: Int = 0,
    val total: Int = 0,
    val phase: BeetEventSyncPhase = BeetEventSyncPhase.Idle,
) {
    val progress: Float
        get() = if (total <= 0) 0f else transferred.toFloat() / total.toFloat()
}
