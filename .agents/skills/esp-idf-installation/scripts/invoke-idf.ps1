param(
    [switch]$FullOutput,
    [string]$IdfArgsJson,
    [string]$IdfArgsPacked,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$IdfArgs
)

$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\..\.."))
. (Join-Path $repoRoot "scripts\shared\BeetScriptOutput.ps1")
$outputMode = Resolve-BeetOutputMode -FullOutput:$FullOutput

$env:IDF_PATH = "C:\esp\v6.0\esp-idf"
$env:IDF_PYTHON_ENV_PATH = "C:\Espressif\tools\python\v6.0\venv"
$env:IDF_TOOLS_PATH = "C:\Espressif\tools"
$env:ESP_IDF_VERSION = "6.0"
$env:ESP_ROM_ELF_DIR = "C:\Espressif\tools\esp-rom-elfs\20241011"
$env:PYTHONIOENCODING = "utf-8"
$env:PYTHONUTF8 = "1"

$toolPaths = @(
    "C:\Espressif\tools\python\v6.0\venv\Scripts",
    "C:\Espressif\tools\cmake\4.0.3\bin",
    "C:\Espressif\tools\ninja\1.12.1",
    "C:\Espressif\tools\xtensa-esp-elf\esp-15.2.0_20251204\xtensa-esp-elf\bin",
    "C:\Espressif\tools\riscv32-esp-elf\esp-15.2.0_20251204\riscv32-esp-elf\bin",
    "C:\Espressif\tools\esp32ulp-elf\2.38_20240113\esp32ulp-elf\bin"
)
$currentPathEntries = $env:Path -split ";" | Where-Object { $_ }
$env:Path = (($toolPaths + $currentPathEntries) | Select-Object -Unique) -join ";"

if (-not [string]::IsNullOrWhiteSpace($IdfArgsJson)) {
    $decodedArgs = $IdfArgsJson | ConvertFrom-Json
    if ($decodedArgs -is [System.Array]) {
        $IdfArgs = @($decodedArgs | ForEach-Object { [string]$_ }) + @($IdfArgs)
    } else {
        $IdfArgs = @([string]$decodedArgs) + @($IdfArgs)
    }
}

if (-not [string]::IsNullOrWhiteSpace($IdfArgsPacked)) {
    $packedArgs = $IdfArgsPacked.Split([char]0x1f) | Where-Object { $_ -ne "" }
    $IdfArgs = @($packedArgs | ForEach-Object { [string]$_ }) + @($IdfArgs)
}

$pythonExe = "C:\Espressif\tools\python\v6.0\venv\Scripts\python.exe"
$idfPy = "C:\esp\v6.0\esp-idf\tools\idf.py"
$pythonArgs = @($idfPy) + @($IdfArgs)

$previousLocation = $null
try {
    $previousLocation = Get-Location
    Push-Location (Get-Location).Path
    & $pythonExe @pythonArgs
    $exitCode = $LASTEXITCODE
} finally {
    if ($previousLocation) {
        Pop-Location
    }
}

if ($exitCode -ne 0) {
    throw "idf.py failed with exit code $exitCode."
}

if ($outputMode -eq "reduced") {
    Write-BeetSuccess "idf.py completed."
}

exit $exitCode
