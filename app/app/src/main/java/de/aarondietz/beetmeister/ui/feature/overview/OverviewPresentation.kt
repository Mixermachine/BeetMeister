package de.aarondietz.beetmeister.ui.feature.overview

internal fun runningSinceUnixSeconds(
    connectedAtMillis: Long,
    connectedAtControllerUptimeSeconds: Long,
    fallbackUptimeSeconds: Long,
    nowMillis: Long,
): Long {
    val runningSinceMillis = if (connectedAtMillis > 0L) {
        connectedAtMillis - (connectedAtControllerUptimeSeconds.coerceAtLeast(0L) * 1000L)
    } else {
        nowMillis - (fallbackUptimeSeconds.coerceAtLeast(0L) * 1000L)
    }
    return runningSinceMillis / 1000L
}
