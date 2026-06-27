param(
    [string]$ProjectDir = "firmware/esp-idf",
    [switch]$FullOutput
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\\..")
. (Join-Path $repoRoot "scripts\shared\BeetScriptOutput.ps1")
$outputMode = Resolve-BeetOutputMode -FullOutput:$FullOutput
$scriptContext = New-BeetScriptContext -RepoRoot $repoRoot -ScriptName "run-firmware-qemu-smoke" -Mode $outputMode
$projectPath = Join-Path $repoRoot $ProjectDir
$invokeIdf = Join-Path $repoRoot ".agents\\skills\\esp-idf-installation\\scripts\\invoke-idf.ps1"
$qemu = Get-Command qemu-system-xtensa -ErrorAction SilentlyContinue

if (-not $qemu) {
    $installedQemu = Get-ChildItem "C:\\Espressif\\tools\\tools\\qemu-xtensa" -Recurse -Filter "qemu-system-xtensa.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($installedQemu) {
        $qemu = $installedQemu
        $env:PATH = "$($installedQemu.Directory.FullName);$env:PATH"
    }
}

if (-not $qemu) {
    Write-BeetSuccess "QEMU smoke skipped: qemu-system-xtensa is not installed. Install qemu-xtensa via ESP-IDF tools first."
    exit 0
}

Write-BeetPhase "Running QEMU smoke validation."
$idfArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $invokeIdf
)
if ($FullOutput) {
    $idfArgs += "-FullOutput"
}
$idfArgs += "qemu"
Invoke-BeetProcess `
    -Context $scriptContext `
    -Description "idf-qemu" `
    -FilePath "powershell.exe" `
    -ArgumentList $idfArgs `
    -WorkingDirectory $projectPath | Out-Null

Write-BeetSuccess "QEMU smoke validation completed."
if ($outputMode -eq "reduced") {
    Write-BeetSuccess "Detailed logs: $(Get-BeetLogDirectory -Context $scriptContext)"
}
