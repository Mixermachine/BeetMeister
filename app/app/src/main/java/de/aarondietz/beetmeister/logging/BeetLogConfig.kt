package de.aarondietz.beetmeister.logging

import android.content.Context
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.android.LogcatAppender
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy
import ch.qos.logback.core.util.FileSize
import org.slf4j.LoggerFactory
import java.io.File

object BeetLogConfig {
    fun init(context: Context) {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        loggerContext.reset()

        val logcatAppender = LogcatAppender().apply {
            this.context = loggerContext
            this.name = "LOGCAT"
            val encoder = PatternLayoutEncoder().apply {
                this.context = loggerContext
                this.pattern = "%msg%n"
                start()
            }
            this.encoder = encoder
            start()
        }

        val logDir = File(context.cacheDir, "logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        val logFilePath = File(logDir, "beet.log").absolutePath
        val fileNamePatternPath = File(logDir, "beet.%i.log").absolutePath

        val fileAppender = RollingFileAppender<ILoggingEvent>().apply appender@{
            this.context = loggerContext
            this.name = "FILE"
            this.file = logFilePath

            val encoder = PatternLayoutEncoder().apply {
                this.context = loggerContext
                this.pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
                start()
            }
            this.encoder = encoder

            val rollingPolicy = FixedWindowRollingPolicy().apply {
                this.context = loggerContext
                this.setParent(this@appender)
                this.fileNamePattern = fileNamePatternPath
                this.minIndex = 1
                this.maxIndex = 3
                start()
            }
            this.rollingPolicy = rollingPolicy

            val triggeringPolicy = SizeBasedTriggeringPolicy<ILoggingEvent>().apply {
                this.context = loggerContext
                this.setMaxFileSize(FileSize.valueOf("1MB"))
                start()
            }
            this.triggeringPolicy = triggeringPolicy

            start()
        }

        val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.level = Level.INFO
        rootLogger.addAppender(logcatAppender)
        rootLogger.addAppender(fileAppender)
    }
}
