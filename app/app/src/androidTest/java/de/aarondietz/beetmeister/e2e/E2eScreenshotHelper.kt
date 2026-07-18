package de.aarondietz.beetmeister.e2e

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Shared helper for the E2E test classes to capture step screenshots
 * (after-connect / before-save / after-readback) from inside Kotlin.
 *
 * Writes PNG files to the app's external files directory
 * (`/sdcard/Android/data/<package>/files/e2e_screenshots/`) so the
 * orchestrator can `adb pull` them into the run folder after
 * `am instrument` exits. This is the Kotlin-owned half of the harness's
 * hybrid screenshot ownership model; the orchestrator (Python) only
 * owns the host-initiated on-failure + final screenshots.
 *
 * Usage:
 * ```
 * val helper = E2eScreenshotHelper("freshInstall_afterConnect")
 * helper.captureStep("afterConnect")
 * helper.captureStep("beforeSave", precondition = { /* fast check */ })
 * ```
 *
 * @param testSlug short name used as a directory name under
 *   `e2e_screenshots/` so the orchestrator can identify which
 *   test class the screenshots came from.
 */
internal class E2eScreenshotHelper(
    private val testSlug: String,
    private val uiAutomation: UiAutomation =
        InstrumentationRegistry.getInstrumentation().uiAutomation,
    private val context: Context = InstrumentationRegistry.getInstrumentation().context,
) {
    private val rootDir: File by lazy {
        val base = context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory()
        File(base, "e2e_screenshots/$testSlug").apply { mkdirs() }
    }

    /**
     * Captures the current screen and writes it to
     * `<rootDir>/<stepName>-<millis>.png`. Best-effort: a failed write
     * is logged and the test continues; screenshots are diagnostic
     * not gating.
     */
    fun captureStep(stepName: String) {
        runCatching {
            val bitmap: Bitmap = uiAutomation.takeScreenshot()
            val file = File(rootDir, "${stepName}-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, /* quality = */ 100, out)
            }
            bitmap.recycle()
        }
    }
}
