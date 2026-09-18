package de.aarondietz.beetmeister.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory

object BeetLogLevel {
    fun setDebugEnabled(enabled: Boolean) {
        val loggerContext = LoggerFactory.getILoggerFactory()
        if (loggerContext is ch.qos.logback.classic.LoggerContext) {
            val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
            rootLogger.level = if (enabled) Level.DEBUG else Level.INFO
        }
    }

    fun isDebugEnabled(): Boolean {
        val loggerContext = LoggerFactory.getILoggerFactory()
        if (loggerContext is ch.qos.logback.classic.LoggerContext) {
            val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
            return rootLogger.level == Level.DEBUG
        }
        return false
    }
}
