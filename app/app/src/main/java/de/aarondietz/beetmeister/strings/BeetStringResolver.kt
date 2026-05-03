package de.aarondietz.beetmeister.strings

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

internal interface BeetStringResolver {
    val locale: Locale

    fun get(@StringRes id: Int, vararg args: Any): String
}

internal class AndroidBeetStringResolver(
    private val context: Context,
) : BeetStringResolver {
    override val locale: Locale
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }

    override fun get(id: Int, vararg args: Any): String = context.getString(id, *args)
}

@Composable
internal fun rememberBeetStringResolver(): BeetStringResolver {
    val context = LocalContext.current
    return remember(context) { AndroidBeetStringResolver(context) }
}
