package de.aarondietz.beetmeister

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.aarondietz.beetmeister.ui.BeetAppViewModel
import de.aarondietz.beetmeister.ui.BeetMeisterApp
import de.aarondietz.beetmeister.ui.theme.BeetMeisterTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {
    private val viewModel: BeetAppViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeetMeisterTheme {
                BeetMeisterApp(viewModel = viewModel)
            }
        }
    }
}
