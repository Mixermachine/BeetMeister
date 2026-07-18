package de.aarondietz.beetmeister.e2e

/**
 * Marks a test class or test method as a long-running end-to-end test that
 * exercises the real Android app against the real ESP32-S3 controller.
 *
 * E2E tests are gated by the custom [BeetE2eAwareJUnitRunner] instrumentation
 * runner: they are SKIPPED unless the runner receives the `beetRunE2e=true`
 * instrument argument. This keeps a naive `connectedDebugAndroidTest` (or
 * `am instrument` without the flag) from running the E2E suite.
 *
 * The orchestrator in `test-harness/` sends:
 * ```
 * am instrument -e beetRunE2e true -e package de.aarondietz.beetmeister.e2e \
 *              -e class de.aarondietz.beetmeister.e2e.<SuiteE2ETest>
 * ```
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class E2e
