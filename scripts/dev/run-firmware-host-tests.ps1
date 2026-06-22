param(
    [string]$BuildDir = "firmware/tests/host/build",
    [switch]$FullOutput
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\\..")
. (Join-Path $repoRoot "scripts\shared\BeetScriptOutput.ps1")
$outputMode = Resolve-BeetOutputMode -FullOutput:$FullOutput
$scriptContext = New-BeetScriptContext -RepoRoot $repoRoot -ScriptName "run-firmware-host-tests" -Mode $outputMode
$cmake = "C:\\Espressif\\tools\\cmake\\4.0.3\\bin\\cmake.exe"
$ctest = "C:\\Espressif\\tools\\cmake\\4.0.3\\bin\\ctest.exe"
$ninja = "C:\\Espressif\\tools\\ninja\\1.12.1\\ninja.exe"
$clang = "C:\\Espressif\\tools\\esp-clang\\esp-20.1.1_20250829\\esp-clang\\bin\\clang.exe"
$sourceDir = Join-Path $repoRoot "firmware\\tests\\host"
$resolvedBuildDir = Join-Path $repoRoot $BuildDir
$supportDir = Join-Path $sourceDir "support"
$vswhere = "C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe"

function Import-VsBuildEnvironment {
    if (Get-Command cl.exe -ErrorAction SilentlyContinue) {
        return $true
    }

    if (-not (Test-Path $vswhere)) {
        return $false
    }

    $vsInstall = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $vsInstall) {
        return $false
    }

    $vcvars = Join-Path $vsInstall "VC\\Auxiliary\\Build\\vcvars64.bat"
    if (-not (Test-Path $vcvars)) {
        return $false
    }

    $envDump = & cmd.exe /d /s /c "`"$vcvars`" >nul && set"
    if ($LASTEXITCODE -ne 0) {
        return $false
    }

    foreach ($line in $envDump) {
        $parts = $line -split "=", 2
        if ($parts.Count -eq 2) {
            [Environment]::SetEnvironmentVariable($parts[0], $parts[1], "Process")
        }
    }

    return [bool](Get-Command cl.exe -ErrorAction SilentlyContinue)
}

function Invoke-CompileOnlyValidation {
    Write-BeetPhase "Running compile-only firmware host validation."
    $objDir = Join-Path $sourceDir "objcheck"
    Remove-Item -Recurse -Force $objDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $objDir | Out-Null

    $sources = @(
        (Join-Path $sourceDir "host_tests.c"),
        (Join-Path $sourceDir "host_ble_transport_tests.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_ble.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_ble_codec.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_ble_guard.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_core.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_iface_names.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_event_ring.c"),
        (Join-Path $supportDir "esp_rom_crc.c"),
        (Join-Path $supportDir "ble_test_stubs.c")
    )

    foreach ($source in $sources) {
        $name = [IO.Path]::GetFileNameWithoutExtension($source)
        $extraArgs = @()
        if ($source -like '*host_ble_transport_tests.c' -or
            $source -like '*beet_ble.c' -or
            $source -like '*ble_test_stubs.c') {
            $extraArgs += '-DBEET_HOST_TEST=1'
        }
        Invoke-BeetProcess `
            -Context $scriptContext `
            -Description "compile-$name" `
            -FilePath $clang `
            -ArgumentList @(
                "-I$supportDir",
                "-I$(Join-Path $repoRoot 'firmware\\esp-idf\\components\\beet_firmware\\include')",
                "-Wall", "-Wextra", "-Werror", "-std=c11"
            ) + $extraArgs + @(
                "-c", $source,
                "-o", (Join-Path $objDir "$name.obj")
            ) `
            -WorkingDirectory $repoRoot | Out-Null
    }

    Remove-Item -Recurse -Force $objDir -ErrorAction SilentlyContinue
    Write-BeetSuccess "Host test compile-only validation passed. Native execution requires a Windows MSVC/SDK linker environment."
}

if (-not (Import-VsBuildEnvironment)) {
    Invoke-CompileOnlyValidation
    exit 0
}

Write-BeetPhase "Configuring firmware host tests."
Invoke-BeetProcess `
    -Context $scriptContext `
    -Description "cmake-configure" `
    -FilePath $cmake `
    -ArgumentList @(
        "-S", $sourceDir,
        "-B", $resolvedBuildDir,
        "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=$ninja",
        "-DCMAKE_C_COMPILER=cl"
    ) `
    -WorkingDirectory $repoRoot | Out-Null

Write-BeetPhase "Building firmware host tests."
Invoke-BeetProcess `
    -Context $scriptContext `
    -Description "cmake-build" `
    -FilePath $cmake `
    -ArgumentList @("--build", $resolvedBuildDir) `
    -WorkingDirectory $repoRoot | Out-Null

Write-BeetPhase "Running firmware host tests."
Invoke-BeetProcess `
    -Context $scriptContext `
    -Description "ctest" `
    -FilePath $ctest `
    -ArgumentList @("--test-dir", $resolvedBuildDir, "--output-on-failure") `
    -WorkingDirectory $repoRoot | Out-Null

Write-BeetSuccess "Firmware host tests passed."
if ($outputMode -eq "reduced") {
    Write-BeetSuccess "Detailed logs: $(Get-BeetLogDirectory -Context $scriptContext)"
}
