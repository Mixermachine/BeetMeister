package de.aarondietz.beetmeister.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory

class BeetLogTest {

    @Test
    fun testLogLevelToggle() {
        BeetLogLevel.setDebugEnabled(true)
        assertTrue(BeetLogLevel.isDebugEnabled())

        BeetLogLevel.setDebugEnabled(false)
        assertFalse(BeetLogLevel.isDebugEnabled())
    }

    @Test
    fun testBeetLogDirectAndLambdaMethods() {
        BeetLogLevel.setDebugEnabled(true)

        var lambdaEvaluated = false
        BeetLog.d("TestTag", "debug message")
        BeetLog.d("TestTag") {
            lambdaEvaluated = true
            "debug lambda"
        }
        assertTrue(lambdaEvaluated)

        lambdaEvaluated = false
        BeetLog.i("TestTag", "info message")
        BeetLog.i("TestTag") {
            lambdaEvaluated = true
            "info lambda"
        }
        assertTrue(lambdaEvaluated)

        lambdaEvaluated = false
        BeetLog.w("TestTag", "warn message")
        BeetLog.w("TestTag", "warn with throwable", RuntimeException("test exception"))
        BeetLog.w("TestTag", RuntimeException("test exception")) {
            lambdaEvaluated = true
            "warn lambda"
        }
        assertTrue(lambdaEvaluated)

        lambdaEvaluated = false
        BeetLog.e("TestTag", "error message")
        BeetLog.e("TestTag", "error with throwable", RuntimeException("test exception"))
        BeetLog.e("TestTag", RuntimeException("test exception")) {
            lambdaEvaluated = true
            "error lambda"
        }
        assertTrue(lambdaEvaluated)
    }

    @Test
    fun testTraceLevelWhenExplicitlyEnabled() {
        val loggerContext = LoggerFactory.getILoggerFactory() as? ch.qos.logback.classic.LoggerContext
        loggerContext?.getLogger(Logger.ROOT_LOGGER_NAME)?.level = Level.TRACE

        var lambdaEvaluated = false
        BeetLog.v("TestTag", "trace message")
        BeetLog.v("TestTag") {
            lambdaEvaluated = true
            "trace lambda"
        }
        assertTrue(lambdaEvaluated)
    }

    @Test
    fun testLazyLambdaNotEvaluatedWhenDisabled() {
        BeetLogLevel.setDebugEnabled(false)

        var debugLambdaEvaluated = false
        BeetLog.d("TestTag") {
            debugLambdaEvaluated = true
            "expensive computation"
        }
        assertFalse(debugLambdaEvaluated)
    }
}
