function Resolve-BeetOutputMode {
    param(
        [switch]$FullOutput
    )

    if ($FullOutput) {
        return "full"
    }

    $envMode = [Environment]::GetEnvironmentVariable("BEET_SCRIPT_OUTPUT", "Process")
    if ([string]::IsNullOrWhiteSpace($envMode)) {
        $envMode = [Environment]::GetEnvironmentVariable("BEET_SCRIPT_OUTPUT", "User")
    }

    if ([string]::IsNullOrWhiteSpace($envMode)) {
        return "reduced"
    }

    switch ($envMode.Trim().ToLowerInvariant()) {
        "full" { return "full" }
        "reduced" { return "reduced" }
        default { return "reduced" }
    }
}

function New-BeetScriptContext {
    param(
        [string]$RepoRoot,
        [string]$ScriptName,
        [string]$Mode
    )

    $safeScriptName = ($ScriptName -replace "[^A-Za-z0-9._-]", "-").Trim("-")
    if ([string]::IsNullOrWhiteSpace($safeScriptName)) {
        $safeScriptName = "script"
    }

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $sessionName = "$timestamp-pid$PID"
    $sessionDir = Join-Path (Join-Path (Join-Path $RepoRoot "artifacts\script-logs") $safeScriptName) $sessionName
    New-Item -ItemType Directory -Force -Path $sessionDir | Out-Null

    return [pscustomobject]@{
        RepoRoot = $RepoRoot
        ScriptName = $safeScriptName
        Mode = $Mode
        SessionDir = $sessionDir
        StepCounter = 0
    }
}

function Write-BeetPhase {
    param([string]$Message)
    Write-Host $Message
}

function Write-BeetSuccess {
    param([string]$Message)
    Write-Host $Message
}

function Write-BeetReducedWarning {
    param([string]$Message)
    Write-Warning $Message
}

function Get-BeetLogDirectory {
    param($Context)
    return $Context.SessionDir
}

function New-BeetLogPaths {
    param(
        $Context,
        [string]$Description
    )

    $Context.StepCounter++
    $safeDescription = ($Description -replace "[^A-Za-z0-9._-]", "-").Trim("-")
    if ([string]::IsNullOrWhiteSpace($safeDescription)) {
        $safeDescription = "step"
    }
    $prefix = "{0:D2}-{1}" -f $Context.StepCounter, $safeDescription

    return [pscustomobject]@{
        Prefix = $prefix
        StdOut = Join-Path $Context.SessionDir "$prefix.stdout.log"
        StdErr = Join-Path $Context.SessionDir "$prefix.stderr.log"
    }
}

function Get-BeetLogTailText {
    param(
        [string]$Path,
        [int]$TailLines = 40
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return ""
    }

    $lines = Get-Content -LiteralPath $Path -Tail $TailLines -ErrorAction SilentlyContinue
    if (-not $lines) {
        return ""
    }

    return ($lines -join [Environment]::NewLine).Trim()
}

function Get-BeetFailureExcerpt {
    param(
        [string]$StdOutPath,
        [string]$StdErrPath
    )

    $stderrTail = Get-BeetLogTailText -Path $StdErrPath
    if (-not [string]::IsNullOrWhiteSpace($stderrTail)) {
        return $stderrTail
    }

    return Get-BeetLogTailText -Path $StdOutPath
}

function Invoke-BeetProcess {
    param(
        $Context,
        [string]$Description,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory,
        [switch]$Interactive,
        [switch]$CaptureStdOut
    )

    if (-not $CaptureStdOut -and ($Interactive -or ($Context.Mode -eq "full"))) {
        $previousLocation = $null
        try {
            if ($WorkingDirectory) {
                $previousLocation = Get-Location
                Push-Location $WorkingDirectory
            }
            & $FilePath @ArgumentList
            $exitCode = $LASTEXITCODE
        } finally {
            if ($previousLocation) {
                Pop-Location
            }
        }

        if ($exitCode -ne 0) {
            throw "$Description failed with exit code $exitCode."
        }

        return [pscustomobject]@{
            ExitCode = $exitCode
            StdOutPath = $null
            StdErrPath = $null
            StdOutText = $null
        }
    }

    $logPaths = New-BeetLogPaths -Context $Context -Description $Description

    $startInfo = @{
        FilePath = $FilePath
        ArgumentList = $ArgumentList
        RedirectStandardOutput = $logPaths.StdOut
        RedirectStandardError = $logPaths.StdErr
        Wait = $true
        PassThru = $true
    }
    if ($IsWindows) {
        $startInfo.WindowStyle = "Hidden"
    }
    if ($WorkingDirectory) {
        $startInfo.WorkingDirectory = $WorkingDirectory
    }

    $process = Start-Process @startInfo
    $exitCode = $process.ExitCode
    if ($exitCode -ne 0) {
        $excerpt = Get-BeetFailureExcerpt -StdOutPath $logPaths.StdOut -StdErrPath $logPaths.StdErr
        if (-not [string]::IsNullOrWhiteSpace($excerpt)) {
            Write-Error "$Description failed. Last log lines:`n$excerpt"
        } else {
            Write-Error "$Description failed. No log output was captured."
        }
        Write-Error "Full logs: $($logPaths.StdOut) and $($logPaths.StdErr)"
        throw "$Description failed with exit code $exitCode."
    }

    $stdoutText = $null
    if ($CaptureStdOut) {
        $stdoutText = Get-Content -LiteralPath $logPaths.StdOut -Raw
        if ($Context.Mode -eq "full" -and -not [string]::IsNullOrWhiteSpace($stdoutText)) {
            Write-Host $stdoutText.TrimEnd()
        }
        $stderrText = Get-Content -LiteralPath $logPaths.StdErr -Raw -ErrorAction SilentlyContinue
        if ($Context.Mode -eq "full" -and -not [string]::IsNullOrWhiteSpace($stderrText)) {
            [Console]::Error.WriteLine($stderrText.TrimEnd())
        }
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        StdOutPath = $logPaths.StdOut
        StdErrPath = $logPaths.StdErr
        StdOutText = $stdoutText
    }
}

function Get-BeetIdfInteractionMode {
    param(
        [string[]]$IdfArgs
    )

    $interactiveCommands = @("monitor", "menuconfig", "gdb", "gdbgui")
    foreach ($arg in $IdfArgs) {
        if ($interactiveCommands -contains $arg) {
            return "interactive"
        }
    }
    return "noninteractive"
}
