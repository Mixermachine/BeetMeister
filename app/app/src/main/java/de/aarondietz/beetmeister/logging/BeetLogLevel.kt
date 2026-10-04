package de.aarondietz.beetmeister.logging

import android.content.Context
import android.content.SharedPreferences
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory

object BeetLogLevel {
    private const val PREFS_NAME = "beet_logging_prefs"
    private const val KEY_DEBUG_ENABLED = "debug_logging_enabled"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs?.getBoolean(KEY_DEBUG_ENABLED, false) ?: false
        applyLevel(enabled)
    }

    fun setDebugEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_DEBUG_ENABLED, enabled)?.apply()
        applyLevel(enabled)
    }

    fun isDebugEnabled(): Boolean {
        prefs?.let {
            return it.getBoolean(KEY_DEBUG_ENABLED, false)
        }
        val loggerContext = LoggerFactory.getILoggerFactory()
        if (loggerContext is LoggerContext) {
            val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
            return rootLogger.level == Level.DEBUG
        }
        return false
    }

    private fun applyLevel(enabled: Boolean) {
        val loggerContext = LoggerFactory.getILoggerFactory()
        if (loggerContext is LoggerContext) {
            val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
            rootLogger.level = if (enabled) Level.DEBUG else Level.INFO
        }
    }
}

