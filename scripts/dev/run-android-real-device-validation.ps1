param(
    [string]$Serial,
    [string]$InstrumentationClass = "de.aarondietz.beetmeister.ui.feature.connection.MaintenanceUpdateInstrumentationTest",
    [string]$ScenarioName = "unspecified-scenario",
    [string]$LogDir,
    [string]$ControllerPort,
    [int]$ControllerBaudRate = 115200,
    [int]$ControllerCaptureSeconds = 1800,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SkipInstrumentation,
    [switch]$KeepLogcatRunning,
    [switch]$KeepControllerCaptureRunning
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$appRoot = Join-Path $repoRoot "app"
$appApk = Join-Path $appRoot "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $appRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$benchCaptureScript = Join-Path $repoRoot "scripts\dev\capture-bench-diagnostics.ps1"
$appPackage = "de.aarondietz.beetmeister"

function Get-ConnectedDevices {
    $lines = & adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "adb devices failed."
    }

    return @(
        $lines |
            Select-Object -Skip 1 |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -match "\S+\s+device$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )
}

function Invoke-Adb {
    param(
        [string[]]$AdbArgs
    )

    if (-not $AdbArgs -or $AdbArgs.Count -eq 0) {
        throw "Invoke-Adb called without adb arguments."
    }

    if ([string]::IsNullOrWhiteSpace($script:SelectedSerial)) {
        & adb @AdbArgs
    } else {
        & adb "-s" $script:SelectedSerial @AdbArgs
    }

    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb $($AdbArgs -join ' ')"
    }
}

function Get-AdbOutput {
    param(
        [string[]]$AdbArgs
    )

    if (-not $AdbArgs -or $AdbArgs.Count -eq 0) {
        throw "Get-AdbOutput called without adb arguments."
    }

    if ([string]::IsNullOrWhiteSpace($script:SelectedSerial)) {
        $output = & adb @AdbArgs
    } else {
        $output = & adb "-s" $script:SelectedSerial @AdbArgs
    }

    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed: adb $($AdbArgs -join ' ')"
    }

    return $output
}

function Assert-AndroidPreflightReady {
    $windowDump = Get-AdbOutput -AdbArgs @("shell", "dumpsys", "window")

    if ($windowDump -match "mCurrentFocus=Window\{.* NotificationShade\}") {
        throw "Android device is still on the lockscreen/notification shade. Unlock the phone and leave BeetMeister visible before running Stage 8."
    }

    if ($windowDump -match "mShowingLockscreen=true" -or $windowDump -match "isStatusBarKeyguard=true") {
        throw "Android device is still locked. Unlock the phone and leave BeetMeister visible before running Stage 8."
    }

    if ($windowDump -notmatch [regex]::Escape($appPackage)) {
        Write-Warning "BeetMeister is not the current focused app. Continuing because instrumentation can still launch its own activity."
    }
}

function Grant-BlePermissions {
    Invoke-Adb -AdbArgs @("shell", "pm", "grant", $appPackage, "android.permission.BLUETOOTH_SCAN")
    Invoke-Adb -AdbArgs @("shell", "pm", "grant", $appPackage, "android.permission.BLUETOOTH_CONNECT")
}

$devices = Get-ConnectedDevices
if ([string]::IsNullOrWhiteSpace($Serial)) {
    if ($devices.Count -eq 0) {
        throw "No Android device connected. Attach a phone with USB debugging enabled and retry."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple Android devices connected. Re-run with -Serial <deviceSerial>."
    }
    $script:SelectedSerial = $devices[0]
} else {
    if ($devices -notcontains $Serial) {
        throw "Requested device serial '$Serial' is not connected."
    }
    $script:SelectedSerial = $Serial
}

if ([string]::IsNullOrWhiteSpace($LogDir)) {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $LogDir = Join-Path $repoRoot "artifacts\stage8\$timestamp"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

$logcatFile = Join-Path $LogDir "android-logcat.txt"
$logcatErrFile = Join-Path $LogDir "android-logcat.stderr.txt"
$instrumentationFile = Join-Path $LogDir "android-instrumentation.txt"
$logcatPidFile = Join-Path $LogDir "logcat.pid"
$controllerLogFile = Join-Path $LogDir "controller-serial.txt"
$controllerPidFile = Join-Path $LogDir "controller-capture.pid"
$notesFile = Join-Path $LogDir "scenario-notes.md"

if (-not $SkipBuild) {
    Push-Location $appRoot
    try {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed."
        }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $appApk)) {
    throw "App APK not found at $appApk"
}
if (-not (Test-Path $testApk)) {
    throw "Android test APK not found at $testApk"
}

if (-not $SkipInstall) {
    Invoke-Adb -AdbArgs @("install", "-r", $appApk)
    Invoke-Adb -AdbArgs @("install", "-r", $testApk)
}
Grant-BlePermissions

Invoke-Adb -AdbArgs @("logcat", "-c")
$logcatArgs = @()
if (-not [string]::IsNullOrWhiteSpace($script:SelectedSerial)) {
    $logcatArgs += @("-s", $script:SelectedSerial)
}
$logcatArgs += @("logcat", "-v", "threadtime")
$logcatProcess = Start-Process -FilePath "adb" -ArgumentList $logcatArgs -RedirectStandardOutput $logcatFile -RedirectStandardError $logcatErrFile -PassThru -WindowStyle Hidden
$logcatProcess.Id | Set-Content -Path $logcatPidFile -NoNewline

if (-not [string]::IsNullOrWhiteSpace($ControllerPort)) {
    if (-not (Test-Path $benchCaptureScript)) {
        throw "Bench capture script not found at $benchCaptureScript"
    }

    $controllerArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $benchCaptureScript,
        "-Port", $ControllerPort,
        "-BaudRate", "$ControllerBaudRate",
        "-DurationSeconds", "$ControllerCaptureSeconds",
        "-OutputPath", $controllerLogFile,
        "-NoSummary"
    )
    $controllerProcess = Start-Process -FilePath "powershell" -ArgumentList $controllerArgs -PassThru -WindowStyle Hidden
    $controllerProcess.Id | Set-Content -Path $controllerPidFile -NoNewline
}

$notes = @"
# Stage 8 Scenario Notes

- Scenario: $ScenarioName
- Android device serial: $script:SelectedSerial
- Controller port: $(if ([string]::IsNullOrWhiteSpace($ControllerPort)) { "not captured" } else { $ControllerPort })
- Started at: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")

## Evidence

- Android logcat: $(Split-Path -Leaf $logcatFile)
- Android instrumentation: $(Split-Path -Leaf $instrumentationFile)
- Controller serial log: $(if ([string]::IsNullOrWhiteSpace($ControllerPort)) { "not captured" } else { Split-Path -Leaf $controllerLogFile })

## Observations

- Result:
- Firmware build label:
- Controller hardware revision:
- Notes:

## Scenario Checklist

- [ ] Bundled firmware success path
- [ ] User abort during upload
- [ ] Android app crash during upload
- [ ] BLE disconnect with successful resume
- [ ] BLE disconnect with session expiry
- [ ] Session invalidation by newer updater
- [ ] Low-battery rejection
- [ ] Busy or watering-active rejection
- [ ] Custom image flow
- [ ] Runtime protocol mismatch warning and override
- [ ] Rollback on failed first boot
"@
$notes | Set-Content -Path $notesFile

try {
    Assert-AndroidPreflightReady

    if (-not $SkipInstrumentation) {
        $instrumentationCommand = @(
            "shell",
            "am",
            "instrument",
            "-w",
            "-e",
            "class",
            $InstrumentationClass,
            "de.aarondietz.beetmeister.test/androidx.test.runner.AndroidJUnitRunner"
        )
        $instrumentationOutput = & adb "-s" $script:SelectedSerial @instrumentationCommand
        if ($LASTEXITCODE -ne 0) {
            throw "Instrumentation run failed."
        }
        $instrumentationOutput | Set-Content -Path $instrumentationFile
    }
} finally {
    if (-not $KeepLogcatRunning -and $logcatProcess -and -not $logcatProcess.HasExited) {
        Stop-Process -Id $logcatProcess.Id -Force
    }
    if (-not $KeepControllerCaptureRunning -and $controllerProcess -and -not $controllerProcess.HasExited) {
        Stop-Process -Id $controllerProcess.Id -Force
    }
}

Write-Host "Stage 8 Android validation scaffolding complete."
Write-Host "Device serial: $script:SelectedSerial"
Write-Host "Log directory: $LogDir"
if (-not $SkipInstrumentation) {
    Write-Host "Instrumentation output: $instrumentationFile"
}
Write-Host "Logcat output: $logcatFile"
Write-Host "Scenario notes: $notesFile"
if (-not [string]::IsNullOrWhiteSpace($ControllerPort)) {
    Write-Host "Controller serial output: $controllerLogFile"
}
if ($KeepLogcatRunning) {
    Write-Host "Logcat is still running. Stop it later with: Stop-Process -Id $(Get-Content '$logcatPidFile')"
}
if ($KeepControllerCaptureRunning -and -not [string]::IsNullOrWhiteSpace($ControllerPort)) {
    Write-Host "Controller capture is still running. Stop it later with: Stop-Process -Id $(Get-Content '$controllerPidFile')"
}
