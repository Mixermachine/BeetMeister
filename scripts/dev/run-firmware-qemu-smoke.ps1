param(
    [string]$ProjectDir = "firmware/esp-idf"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\\..")
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
    Write-Host "QEMU smoke skipped: qemu-system-xtensa is not installed. Install qemu-xtensa via ESP-IDF tools first."
    exit 0
}

Push-Location $projectPath
try {
    powershell -ExecutionPolicy Bypass -File $invokeIdf qemu
}
finally {
    Pop-Location
}
