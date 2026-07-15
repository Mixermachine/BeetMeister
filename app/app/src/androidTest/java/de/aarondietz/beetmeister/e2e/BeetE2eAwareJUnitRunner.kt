package de.aarondietz.beetmeister.e2e

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter

class BeetE2eAwareJUnitRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        val runE2e = arguments.getString(ARG_BEET_RUN_E2E)?.toBooleanStrictOrNull() == true
        if (runE2e) {
            super.onCreate(arguments)
            return
        }
        // Register a RunListener that filters tests at runtime.
        // The default AndroidJUnit4ClassRunner is preserved; we just
        // intercept test execution to skip @E2e-annotated tests.
        // This is clean — no reflection, no RunnerBuilder hacks.
        val listener = E2eFilterRunListener()
        arguments.putString(
            ARG_LISTENER,
            E2eFilterRunListener::class.java.name,
        )
        super.onCreate(arguments)
    }

    private companion object {
        const val ARG_BEET_RUN_E2E = "beetRunE2e"
        // Mirror of AndroidJUnitRunner.ARGUMENT_LISTENER = "listener"
        const val ARG_LISTENER = "listener"
    }
}

internal object E2eSkipFilter : Filter() {

    override fun shouldRun(description: Description): Boolean {
        if (description.isTest) {
            description.methodName?.let { methodName ->
                runCatching {
                    val testClass = Class.forName(description.className)
                    val method = testClass.getMethod(methodName)
                    if (method.isAnnotationPresent(E2e::class.java)) return false
                }
            }
        }
        runCatching {
            val testClass = Class.forName(description.className)
            if (testClass.isAnnotationPresent(E2e::class.java)) return false
        }
        return true
    }

    override fun describe(): String =
        "BeetE2eSkipFilter: drops @E2e-annotated tests unless beetRunE2e=true"
}

/**
 * JUnit [org.junit.runner.notification.RunListener] that replaces
 * every @E2e-annotated test with an ignored result.
 *
 * We cannot use [Filter] without reflection on the private `request`
 * field. A RunListener can mark tests as ignored AFTER the runner
 * dispatches them, which is good enough for our gate.
 */
internal class E2eFilterRunListener : org.junit.runner.notification.RunListener() {

    override fun testStarted(description: Description) {
        if (!E2eSkipFilter.shouldRun(description)) {
            throw org.junit.internal.AssumptionViolatedException(
                "Skipped by BeetE2eGate (beetRunE2e not true)",
            )
        }
    }
}
