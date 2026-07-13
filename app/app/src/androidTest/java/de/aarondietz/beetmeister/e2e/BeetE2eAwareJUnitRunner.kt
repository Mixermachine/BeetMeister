package de.aarondietz.beetmeister.e2e

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import org.junit.internal.builders.AllDefaultPossibilitiesBuilder
import org.junit.runner.Runner
import org.junit.runner.manipulation.Filter
import org.junit.runner.manipulation.Filterable
import org.junit.runners.model.RunnerBuilder

/**
 * Custom instrumentation runner for the BeetMeister app.
 *
 * Skips every [E2e]-annotated test class and [E2e]-annotated test method
 * unless the runner is invoked with the `beetRunE2e=true` instrument
 * argument. This is the gate the test-harness orchestrator flips on:
 *
 *   am instrument -e beetRunE2e true -e package de.aarondietz.beetmeister.e2e ...
 *
 * The existing pure-UI smoke test
 * (`de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateInstrumentationTest`)
 * does NOT carry the @E2e annotation, so it is unaffected and still runs
 * as the smoke gate in every orchestrator run.
 *
 * Implementation note: AndroidJUnitRunner 1.7.0 does not expose a public
 * `getFilters()` override point. Instead, the runner reads a comma-separated
 * list of `RunnerBuilder` class names from the `runnerBuilder` Bundle key
 * (`RunnerArgs.ARGUMENT_RUNNER_BUILDER`), instantiates each via its
 * no-argument constructor, and inserts them at the head of the
 * `TestRequestBuilder` chain (the default `AndroidRunnerBuilder` becomes
 * the tail). The first builder that returns a non-null `Runner` wins.
 *
 * This class therefore:
 *  1. Reads `beetRunE2e` in [onCreate].
 *  2. If absent/false, copies the incoming [Bundle], adds the
 *     `runnerBuilder` key pointing to [E2eAwareRunnerBuilder], and passes
 *     the modified bundle to `super.onCreate(...)`. The super parses the
 *     modified bundle, builds the [E2eAwareRunnerBuilder], and threads
 *     the filter into every Runner it constructs.
 *  3. If `beetRunE2e=true`, the bundle is passed through unchanged, so
 *     every test (including @E2e) runs.
 */
class BeetE2eAwareJUnitRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        val runE2e = arguments.getString(ARG_BEET_RUN_E2E)?.toBooleanStrictOrNull() == true
        if (runE2e) {
            super.onCreate(arguments)
            return
        }
        val modified = Bundle(arguments)
        val existing = modified.getString(ARGUMENT_RUNNER_BUILDER)
        val builderClass = E2eAwareRunnerBuilder::class.java.name
        modified.putString(
            ARGUMENT_RUNNER_BUILDER,
            if (existing.isNullOrBlank()) builderClass else "$existing,$builderClass",
        )
        super.onCreate(modified)
    }

    private companion object {
        const val ARG_BEET_RUN_E2E = "beetRunE2e"

        // Mirrors androidx.test.internal.runner.RunnerArgs.ARGUMENT_RUNNER_BUILDER,
        // which is package-private in androidx.test:runner:1.7.0. The
        // string value ("runnerBuilder") is the stable contract observed
        // across all AndroidX Test versions.
        const val ARGUMENT_RUNNER_BUILDER = "runnerBuilder"
    }
}

/**
 * JUnit 4 [RunnerBuilder] installed by [BeetE2eAwareJUnitRunner] when
 * `beetRunE2e` is not `true`.
 *
 * Scope is intentionally narrow: this builder only intercepts test
 * classes that carry the [E2e] annotation (or whose methods do). For
 * every other class it returns `null` so the next builder in the
 * chain — the default `AndroidRunnerBuilder` — handles them with the
 * full Android-specific behaviour (AndroidJUnit4ClassRunner, the
 * @SdkSuppress / @RequiresDevice hooks, the orchestrator-friendly
 * `AndroidLogOnlyBuilder`, etc.). That keeps the runner delta
 * scoped to exactly the @E2e-bearing classes and avoids shadowing
 * the default Android path for every other instrumentation test.
 *
 * For the classes it does handle, the delegate
 * [AllDefaultPossibilitiesBuilder] constructs the standard JUnit
 * Runner and [E2eSkipFilter] is applied via [Filterable.filter], so
 * any @E2e-annotated method inside the class is dropped before
 * JUnit runs it.
 *
 * The no-argument constructor is required by the AndroidX Test
 * runner factory (`AndroidRunnerBuilder.instantiateRunnerBuilders`),
 * which instantiates custom RunnerBuilders reflectively via
 * `getDeclaredConstructor()`.
 */
internal class E2eAwareRunnerBuilder : RunnerBuilder() {
    private val delegate: RunnerBuilder = AllDefaultPossibilitiesBuilder()

    override fun runnerForClass(testClass: Class<*>): Runner? {
        if (!isE2eClass(testClass)) return null
        val runner = delegate.runnerForClass(testClass) ?: return null
        if (runner is Filterable) {
            runner.filter(E2eSkipFilter)
        }
        return runner
    }

    private fun isE2eClass(testClass: Class<*>): Boolean {
        if (testClass.isAnnotationPresent(E2e::class.java)) return true
        return testClass.methods.any { it.isAnnotationPresent(E2e::class.java) }
    }
}

/**
 * JUnit4 [Filter] that drops every test whose method or enclosing class
 * carries the [E2e] annotation. Applied by [E2eAwareRunnerBuilder] to
 * every Runner it returns. No-op for tests without the annotation.
 */
internal object E2eSkipFilter : Filter() {

    override fun shouldRun(description: org.junit.runner.Description): Boolean {
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
