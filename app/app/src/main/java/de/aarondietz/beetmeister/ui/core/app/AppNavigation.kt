package de.aarondietz.beetmeister.ui.core.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import de.aarondietz.beetmeister.R

internal enum class TopLevelScreen(@StringRes val labelRes: Int, val icon: @Composable () -> Unit) {
    Overview(R.string.nav_overview, { Icon(Icons.Default.Grass, contentDescription = null) }),
    Calibration(R.string.nav_calibration, { Icon(Icons.Default.Tune, contentDescription = null) }),
    Events(R.string.nav_events, { Icon(Icons.Default.Event, contentDescription = null) }),
    Settings(R.string.nav_settings, { Icon(Icons.Default.Settings, contentDescription = null) }),
}

internal sealed interface AppRoute {
    val parentTopLevelScreen: TopLevelScreen

    data class TopLevel(val screen: TopLevelScreen) : AppRoute {
        override val parentTopLevelScreen: TopLevelScreen = screen
    }

    data class PairDetail(
        val pairIndex: Int,
        override val parentTopLevelScreen: TopLevelScreen,
    ) : AppRoute

    data object WateringHistory : AppRoute {
        override val parentTopLevelScreen: TopLevelScreen = TopLevelScreen.Events
    }

    data object ValveCalibration : AppRoute {
        override val parentTopLevelScreen: TopLevelScreen = TopLevelScreen.Settings
    }
}

internal val AppRouteSaver: Saver<AppRoute, String> = Saver(
    save = { route -> encodeAppRoute(route) },
    restore = { encoded -> decodeAppRoute(encoded) },
)

internal val AppRouteStackSaver = listSaver<List<AppRoute>, String>(
    save = { stack -> stack.map { route -> encodeAppRoute(route) } },
    restore = { encoded: List<String> ->
        encoded.mapNotNull(::decodeAppRoute).ifEmpty {
            listOf(AppRoute.TopLevel(TopLevelScreen.Overview))
        }
    },
)

private fun encodeAppRoute(route: AppRoute): String = when (route) {
    is AppRoute.TopLevel -> "top:${route.screen.name}"
    is AppRoute.PairDetail -> "pair:${route.parentTopLevelScreen.name}:${route.pairIndex}"
    AppRoute.WateringHistory -> "events:watering-history"
    AppRoute.ValveCalibration -> "settings:valve-calibration"
}

private fun decodeAppRoute(encoded: String): AppRoute? {
    val parts = encoded.split(':')
    return when (parts.firstOrNull()) {
        "top" -> parts.getOrNull(1)?.let(::decodeTopLevel)?.let(AppRoute::TopLevel)
        "pair" -> {
            val parent = parts.getOrNull(1)?.let(::decodeTopLevel)
            val pairIndex = parts.getOrNull(2)?.toIntOrNull()
            if (parent != null && pairIndex != null) {
                AppRoute.PairDetail(pairIndex = pairIndex, parentTopLevelScreen = parent)
            } else {
                null
            }
        }
        "events" -> if (parts.getOrNull(1) == "watering-history") AppRoute.WateringHistory else null
        "settings" -> if (parts.getOrNull(1) == "valve-calibration") AppRoute.ValveCalibration else null
        else -> null
    }
}

private fun decodeTopLevel(encoded: String): TopLevelScreen? =
    TopLevelScreen.entries.firstOrNull { it.name == encoded }
