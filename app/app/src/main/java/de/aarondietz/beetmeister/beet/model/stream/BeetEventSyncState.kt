package de.aarondietz.beetmeister.beet.model.stream

data class BeetEventSyncState(
    val active: Boolean = false,
    val downloaded: Int = 0,
    val total: Int = 0,
    val phase: BeetEventSyncPhase = BeetEventSyncPhase.Idle,
) {
    val progress: Float
        get() = if (total <= 0) 0f else downloaded.toFloat() / total.toFloat()
}
