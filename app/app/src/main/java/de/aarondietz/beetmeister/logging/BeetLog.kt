package de.aarondietz.beetmeister.logging

import org.slf4j.LoggerFactory
import java.util.function.Supplier

object BeetLog {
    fun v(tag: String, msg: String) {
        LoggerFactory.getLogger(tag).trace(msg)
    }

    fun v(tag: String, msg: () -> String) {
        LoggerFactory.getLogger(tag).atTrace().setMessage(Supplier { msg() }).log()
    }

    fun d(tag: String, msg: String) {
        LoggerFactory.getLogger(tag).debug(msg)
    }

    fun d(tag: String, msg: () -> String) {
        LoggerFactory.getLogger(tag).atDebug().setMessage(Supplier { msg() }).log()
    }

    fun i(tag: String, msg: String) {
        LoggerFactory.getLogger(tag).info(msg)
    }

    fun i(tag: String, msg: () -> String) {
        LoggerFactory.getLogger(tag).atInfo().setMessage(Supplier { msg() }).log()
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            LoggerFactory.getLogger(tag).warn(msg, throwable)
        } else {
            LoggerFactory.getLogger(tag).warn(msg)
        }
    }

    fun w(tag: String, throwable: Throwable? = null, msg: () -> String) {
        val logger = LoggerFactory.getLogger(tag)
        if (throwable != null) {
            logger.atWarn().setCause(throwable).setMessage(Supplier { msg() }).log()
        } else {
            logger.atWarn().setMessage(Supplier { msg() }).log()
        }
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            LoggerFactory.getLogger(tag).error(msg, throwable)
        } else {
            LoggerFactory.getLogger(tag).error(msg)
        }
    }

    fun e(tag: String, throwable: Throwable? = null, msg: () -> String) {
        val logger = LoggerFactory.getLogger(tag)
        if (throwable != null) {
            logger.atError().setCause(throwable).setMessage(Supplier { msg() }).log()
        } else {
            logger.atError().setMessage(Supplier { msg() }).log()
        }
    }
}
