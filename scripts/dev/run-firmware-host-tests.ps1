param(
    [string]$BuildDir = "firmware/tests/host/build"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\\..")
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
    $objDir = Join-Path $sourceDir "objcheck"
    Remove-Item -Recurse -Force $objDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $objDir | Out-Null

    $sources = @(
        (Join-Path $sourceDir "host_tests.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_ble_codec.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_core.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_event_ring.c"),
        (Join-Path $repoRoot "firmware\\esp-idf\\components\\beet_firmware\\src\\beet_iface_names.c"),
        (Join-Path $supportDir "esp_rom_crc.c")
    )

    foreach ($source in $sources) {
        $name = [IO.Path]::GetFileNameWithoutExtension($source)
        & $clang `
            "-I$supportDir" `
            "-I$(Join-Path $repoRoot 'firmware\\esp-idf\\components\\beet_firmware\\include')" `
            -Wall -Wextra -Werror -std=c11 `
            -c $source `
            -o (Join-Path $objDir "$name.obj")
        if ($LASTEXITCODE -ne 0) {
            throw "Compile-only validation failed for $source"
        }
    }

    Remove-Item -Recurse -Force $objDir -ErrorAction SilentlyContinue
    Write-Host "Host test compile-only validation passed. Native execution requires a Windows MSVC/SDK linker environment."
}

if (-not (Import-VsBuildEnvironment)) {
    Invoke-CompileOnlyValidation
    exit 0
}

& $cmake -S $sourceDir -B $resolvedBuildDir -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$ninja" `
    "-DCMAKE_C_COMPILER=cl"

& $cmake --build $resolvedBuildDir
& $ctest --test-dir $resolvedBuildDir --output-on-failure
