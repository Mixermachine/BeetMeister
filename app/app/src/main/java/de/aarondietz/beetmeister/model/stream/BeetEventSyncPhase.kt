package de.aarondietz.beetmeister.model.stream

enum class BeetEventSyncPhase {
    Idle,
    CatchingUp,
    PausedForCommand,
    Completed,
}
