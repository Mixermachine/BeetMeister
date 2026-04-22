package de.aarondietz.beetmeister

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import de.aarondietz.beetmeister.ui.theme.BeetMeisterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeetMeisterTheme {
                BeetMeisterApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun BeetMeisterApp() {
    val backStack = rememberSaveable(saver = appDestinationBackStackSaver()) {
        mutableStateListOf<AppDestination>(AppDestination.Home)
    }
    val currentDestination = backStack.lastOrNull() ?: AppDestination.Home
    val entryProvider = remember {
        entryProvider<AppDestination> {
            entry<AppDestination.Home> {
                DestinationScreen(
                    title = "Home",
                    description = "Track your latest beet creations from the main dashboard."
                )
            }
            entry<AppDestination.Favorites> {
                DestinationScreen(
                    title = "Favorites",
                    description = "Keep the best beet mixes close and revisit them quickly."
                )
            }
            entry<AppDestination.Profile> {
                DestinationScreen(
                    title = "Profile",
                    description = "Manage your preferences and app profile settings here."
                )
            }
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestination.topLevelDestinations.forEach {
                item(
                    icon = {
                        Icon(
                            painter = painterResource(it.icon),
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { backStack.navigateToTopLevel(it) }
                )
            }
        }
    ) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            entryProvider = entryProvider
        )
    }
}

sealed interface AppDestination : NavKey {
    val id: String
    val label: String
    val icon: Int

    data object Home : AppDestination {
        override val id = "home"
        override val label = "Home"
        override val icon = R.drawable.ic_home
    }

    data object Favorites : AppDestination {
        override val id = "favorites"
        override val label = "Favorites"
        override val icon = R.drawable.ic_favorite
    }

    data object Profile : AppDestination {
        override val id = "profile"
        override val label = "Profile"
        override val icon = R.drawable.ic_account_box
    }

    companion object {
        val topLevelDestinations = listOf(Home, Favorites, Profile)

        fun fromId(id: String): AppDestination =
            topLevelDestinations.firstOrNull { it.id == id } ?: Home
    }
}

private fun SnapshotStateList<AppDestination>.navigateToTopLevel(destination: AppDestination) {
    if (lastOrNull() == destination) {
        return
    }

    clear()
    add(destination)
}

private fun appDestinationBackStackSaver(): Saver<SnapshotStateList<AppDestination>, Any> =
    listSaver(
        save = { backStack -> backStack.map(AppDestination::id) },
        restore = { savedIds ->
            val restoredDestinations = savedIds.map(AppDestination::fromId)
            if (restoredDestinations.isEmpty()) {
                mutableStateListOf(AppDestination.Home)
            } else {
                mutableStateListOf(*restoredDestinations.toTypedArray())
            }
        }
    )

@Composable
private fun DestinationScreen(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = description,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Start
        )
    }
}

@Preview(showBackground = true)
@Composable
fun BeetMeisterAppPreview() {
    BeetMeisterTheme {
        BeetMeisterApp()
    }
}
