param(
    [string]$Port = "COM7",
    [int]$BaudRate = 115200,
    [int]$DurationSeconds = 15,
    [string]$OutputPath = "",
    [switch]$BenchOnly,
    [switch]$NoSummary,
    [switch]$FullOutput
)

$ErrorActionPreference = "Stop"
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
. (Join-Path $repoRoot "scripts\shared\BeetScriptOutput.ps1")
$outputMode = Resolve-BeetOutputMode -FullOutput:$FullOutput
$scriptContext = New-BeetScriptContext -RepoRoot $repoRoot -ScriptName "capture-bench-diagnostics" -Mode $outputMode

$serialPort = New-Object System.IO.Ports.SerialPort $Port, $BaudRate, ([System.IO.Ports.Parity]::None), 8, ([System.IO.Ports.StopBits]::One)
$serialPort.ReadTimeout = 200
$serialPort.DtrEnable = $false
$serialPort.RtsEnable = $false
$serialPort.NewLine = "`n"

$allLines = New-Object System.Collections.Generic.List[string]
$pendingFragment = ""

function Add-CapturedLine {
    param(
        [string]$Line
    )

    if ([string]::IsNullOrWhiteSpace($Line)) {
        return
    }

    $trimmed = $Line.Trim()
    $allLines.Add($trimmed)

    if ($OutputPath) {
        [System.IO.File]::AppendAllText($OutputPath, $trimmed + [Environment]::NewLine)
    }
}

if (-not $OutputPath) {
    $OutputPath = Join-Path (Get-BeetLogDirectory -Context $scriptContext) "serial-capture.txt"
}

if ($OutputPath) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [System.IO.File]::WriteAllText($OutputPath, "")
}

try {
    $serialPort.Open()
    $deadline = (Get-Date).AddSeconds($DurationSeconds)

    while ((Get-Date) -lt $deadline) {
        $chunk = $serialPort.ReadExisting()
        if (-not [string]::IsNullOrEmpty($chunk)) {
            $normalized = ($pendingFragment + $chunk) -replace "`r", ""
            $lines = $normalized -split "`n"
            $lastIndex = $lines.Length - 1

            for ($i = 0; $i -lt $lastIndex; $i++) {
                $line = $lines[$i]
                Add-CapturedLine -Line $line
            }

            $pendingFragment = $lines[$lastIndex]
        }
        Start-Sleep -Milliseconds 100
    }
}
finally {
    if ($serialPort.IsOpen) {
        $serialPort.Close()
    }
    $serialPort.Dispose()
}

if (-not [string]::IsNullOrWhiteSpace($pendingFragment)) {
    Add-CapturedLine -Line $pendingFragment
}

$displayLines = if ($BenchOnly) {
    $allLines | Where-Object {
        $_ -match '^[IWE] \(\d+\) beet_controller:' -and
        ($_ -match 'bench ' -or $_ -match 'battery spike' -or $_ -match 'recovered from sensor fault')
    }
} else {
    $allLines | Where-Object { $_ -match '^[IWE] \(\d+\) ' }
}

if ($outputMode -eq "full") {
    foreach ($line in $displayLines) {
        Write-Output $line
    }
}

if ($NoSummary) {
    if ($outputMode -eq "reduced") {
        Write-BeetSuccess "Bench diagnostics captured to $OutputPath"
    }
    return
}

$batteryRegex = [regex]'bench battery raw=(?<raw>-?\d+) sensed_mv=(?<sensed>\d+) divider_mv=(?<divider>\d+) scaled_mv=(?<scaled>\d+) filtered_mv=(?<filtered>\d+) battery_state=(?<state>\S+) active_pumps=(?<pumps>\d+)'
$pairRegex = [regex]'bench pair=(?<pair>\d+) relay_gpio=(?<relay>\d+) moisture_gpio=(?<moist>\d+) raw=(?<raw>-?\d+) mv=(?<mv>\d+) corrected_mv=(?<corrected>\d+) pct=(?<pct>\d+) sample_ok=(?<sample>\d) sensor_valid=(?<valid>\d) state=(?<state>\S+) block=(?<block>\S+)'

$latestBattery = $null
$latestPairs = @{}

foreach ($line in $allLines) {
    $batteryMatch = $batteryRegex.Match($line)
    if ($batteryMatch.Success) {
        $latestBattery = [pscustomobject]@{
            Raw = [int]$batteryMatch.Groups["raw"].Value
            SensedMv = [int]$batteryMatch.Groups["sensed"].Value
            DividerMv = [int]$batteryMatch.Groups["divider"].Value
            ScaledMv = [int]$batteryMatch.Groups["scaled"].Value
            FilteredMv = [int]$batteryMatch.Groups["filtered"].Value
            BatteryState = $batteryMatch.Groups["state"].Value
            ActivePumps = [int]$batteryMatch.Groups["pumps"].Value
        }
        continue
    }

    $pairMatch = $pairRegex.Match($line)
    if ($pairMatch.Success) {
        $pair = [int]$pairMatch.Groups["pair"].Value
        $latestPairs[$pair] = [pscustomobject]@{
            Pair = $pair
            RelayGpio = [int]$pairMatch.Groups["relay"].Value
            MoistureGpio = [int]$pairMatch.Groups["moist"].Value
            Raw = [int]$pairMatch.Groups["raw"].Value
            RawMillivolts = [int]$pairMatch.Groups["mv"].Value
            CorrectedMv = [int]$pairMatch.Groups["corrected"].Value
            MoisturePct = [int]$pairMatch.Groups["pct"].Value
            SampleOk = [int]$pairMatch.Groups["sample"].Value
            SensorValid = [int]$pairMatch.Groups["valid"].Value
            State = $pairMatch.Groups["state"].Value
            Block = $pairMatch.Groups["block"].Value
        }
    }
}

Write-Output ""
Write-Output "Bench Summary"

if ($latestBattery -ne $null) {
    Write-Output ("battery raw={0} sensed_mv={1} divider_mv={2} scaled_mv={3} filtered_mv={4} state={5} active_pumps={6}" -f `
        $latestBattery.Raw, `
        $latestBattery.SensedMv, `
        $latestBattery.DividerMv, `
        $latestBattery.ScaledMv, `
        $latestBattery.FilteredMv, `
        $latestBattery.BatteryState, `
        $latestBattery.ActivePumps)
} else {
    Write-Output "battery no bench battery line captured"
}

if ($latestPairs.Count -gt 0) {
    $latestPairs.Values |
        Sort-Object Pair |
        Format-Table Pair, RelayGpio, MoistureGpio, Raw, RawMillivolts, CorrectedMv, MoisturePct, SampleOk, SensorValid, State, Block
} else {
    Write-Output "pairs no bench pair lines captured"
}

if ($outputMode -eq "reduced") {
    Write-BeetSuccess "Raw serial capture: $OutputPath"
}
