# References and Examples for Android Screenshot Capture

## 1. Validating PNG Integrity

A valid PNG file must always begin with the standard 8-byte magic header:
`89 50 4E 47 0D 0A 1A 0A` (`\x89PNG\r\n\x1a\n`)

When piped through Windows shells (PowerShell, CMD, Git Bash without binary mode), `0A` bytes get transformed into `0D 0A` (CRLF), corrupting chunks like `IHDR` and rendering the image unreadable.

To verify a screenshot:
```python
with open("screenshot.png", "rb") as f:
    assert f.read(8) == b"\x89PNG\r\n\x1a\n", "Corrupt PNG header!"
```

## 2. In-Test Failure Rule Pattern

Example JUnit rule capturing screenshots on test failure:

```kotlin
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class ScreenshotOnFailureRule(private val testSlug: String) : TestWatcher() {
    override fun failed(e: Throwable?, description: Description?) {
        val methodName = description?.methodName ?: "unknown"
        val helper = E2eScreenshotHelper(testSlug)
        helper.captureStep("FAILED_$methodName")
    }
}
```

## 3. Pulling via ADB in Automation Scripts

Python snippet to pull all in-test screenshots after test run:

```python
import subprocess
from pathlib import Path

def pull_e2e_screenshots(dest_dir: str = "artifacts/screenshots") -> None:
    Path(dest_dir).mkdir(parents=True, exist_ok=True)
    remote_path = "/sdcard/Android/data/de.aarondietz.beetmeister/files/e2e_screenshots/"
    subprocess.run(["adb", "pull", remote_path, dest_dir], check=False)
```
