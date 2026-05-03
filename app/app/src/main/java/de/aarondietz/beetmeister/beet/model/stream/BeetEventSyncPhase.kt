package de.aarondietz.beetmeister.beet.model.stream

enum class BeetEventSyncPhase {
    Idle,
    CatchingUp,
    PausedForCommand,
    Completed,
}
