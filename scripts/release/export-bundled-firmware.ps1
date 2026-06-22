param(
    [string]$OutputDir = ".\app\app\build\generated\bundledFirmware\main\assets\firmware",
    [string]$PrebuiltImagePath,
    [string]$FirmwareBuildDir = ".\firmware\esp-idf\build-bundled-app",
    [string]$TargetHardwareRev = "rev_a",
    [string]$AssetFileName = "beetmeister-rev_a-bundled.bin",
    [switch]$FullOutput
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param(
        [string]$RepoRoot,
        [string]$PathValue
    )

    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $PathValue))
}

function Get-GitValue {
    param(
        [string]$RepoRoot,
        [string[]]$GitArgs,
        [string]$Fallback = ""
    )

    try {
        $value = (& git -C $RepoRoot @GitArgs 2>$null)
        if ($LASTEXITCODE -eq 0) {
            return ($value | Select-Object -First 1).Trim()
        }
    } catch {
    }
    return $Fallback
}

function Limit-Value {
    param(
        [string]$Label,
        [string]$Value,
        [int]$MaxLength
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Label must not be empty."
    }
    if ($Value.Length -gt $MaxLength) {
        throw "$Label '$Value' exceeds max length $MaxLength."
    }
    return $Value
}

function Get-StampValues {
    param([string]$RepoRoot)

    $tag = Get-GitValue -RepoRoot $RepoRoot -GitArgs @("tag", "--points-at", "HEAD") -Fallback ""
    $shortSha = Get-GitValue -RepoRoot $RepoRoot -GitArgs @("rev-parse", "--short", "HEAD") -Fallback "unknown"
    $describe = Get-GitValue -RepoRoot $RepoRoot -GitArgs @("describe", "--tags", "--always", "--dirty") -Fallback $shortSha
    $isDirty = $describe.EndsWith("-dirty")

    if ($tag) {
        $firmwareVersion = $tag
        $buildLabel = $tag
    } else {
        $firmwareVersion = if ($isDirty) { "dev-$shortSha-dirty" } else { "dev-$shortSha" }
        $buildLabel = $describe
    }

    return @{
        FirmwareVersion = (Limit-Value -Label "Firmware version" -Value $firmwareVersion -MaxLength 32)
        BuildLabel = (Limit-Value -Label "Build label" -Value $buildLabel -MaxLength 48)
    }
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
. (Join-Path $repoRoot "scripts\shared\BeetScriptOutput.ps1")
$outputMode = Resolve-BeetOutputMode -FullOutput:$FullOutput
$scriptContext = New-BeetScriptContext -RepoRoot $repoRoot -ScriptName "export-bundled-firmware" -Mode $outputMode
$outputPath = Resolve-RepoPath -RepoRoot $repoRoot -PathValue $OutputDir
$firmwareBuildPath = Resolve-RepoPath -RepoRoot $repoRoot -PathValue $FirmwareBuildDir
$assetPath = Join-Path $outputPath $AssetFileName
$metadataTool = Join-Path $repoRoot "firmware\esp-idf\components\beet_firmware\tools\read_beet_metadata.py"
$invokeIdf = Join-Path $repoRoot ".agents\skills\esp-idf-installation\scripts\invoke-idf.ps1"
$releaseConfigCheck = Join-Path $repoRoot "scripts\dev\verify-firmware-release-config.ps1"
$releaseSdkconfigPath = Join-Path $firmwareBuildPath "sdkconfig.release.generated"

if (-not $PrebuiltImagePath) {
    Write-BeetPhase "Building bundled firmware image."
    $stamp = Get-StampValues -RepoRoot $repoRoot
    $env:SDKCONFIG_DEFAULTS = "sdkconfig.defaults;sdkconfig.defaults.esp32s3;sdkconfig.release.defaults"
    $env:SDKCONFIG = $releaseSdkconfigPath
    if (Test-Path -LiteralPath $firmwareBuildPath) {
        Remove-Item -LiteralPath $firmwareBuildPath -Recurse -Force
    }
    if (Test-Path -LiteralPath $releaseSdkconfigPath) {
        Remove-Item -LiteralPath $releaseSdkconfigPath -Force
    }
    $idfArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $invokeIdf
    )
    if ($FullOutput) {
        $idfArgs += "-FullOutput"
    }
    $idfArgs += @(
        "-B", $firmwareBuildPath,
        "-DSDKCONFIG=$releaseSdkconfigPath",
        "-DBEET_FIRMWARE_VERSION=$($stamp.FirmwareVersion)",
        "-DBEET_BUILD_LABEL=$($stamp.BuildLabel)",
        "build"
    )
    Invoke-BeetProcess `
        -Context $scriptContext `
        -Description "build-firmware" `
        -FilePath "powershell.exe" `
        -ArgumentList $idfArgs `
        -WorkingDirectory (Join-Path $repoRoot "firmware\esp-idf") | Out-Null

    Write-BeetPhase "Validating release firmware config."
    $configArgs = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $releaseConfigCheck,
        "-SdkconfigPath", $releaseSdkconfigPath
    )
    if ($FullOutput) {
        $configArgs += "-FullOutput"
    }
    Invoke-BeetProcess `
        -Context $scriptContext `
        -Description "validate-release-config" `
        -FilePath "powershell.exe" `
        -ArgumentList $configArgs `
        -WorkingDirectory $repoRoot | Out-Null
    $sourceImagePath = Join-Path $firmwareBuildPath "beetmeister.bin"
} else {
    Write-BeetPhase "Using prebuilt firmware image."
    $resolvedPrebuiltImage = Resolve-Path -LiteralPath $PrebuiltImagePath
    $sourceImagePath = $resolvedPrebuiltImage.Path
}

if (-not (Test-Path -LiteralPath $sourceImagePath)) {
    throw "Firmware image not found at '$sourceImagePath'."
}

$metadataResult = Invoke-BeetProcess `
    -Context $scriptContext `
    -Description "read-firmware-metadata" `
    -FilePath "python" `
    -ArgumentList @($metadataTool, $sourceImagePath) `
    -WorkingDirectory $repoRoot `
    -CaptureStdOut
$metadataJson = $metadataResult.StdOutText
$metadata = $metadataJson | ConvertFrom-Json

if ($metadata.product_id -ne "beetmeister") {
    throw "Bundled firmware product_id must be 'beetmeister'."
}
if ($metadata.image_kind -ne "bundled") {
    throw "Bundled firmware image_kind must be 'bundled'."
}
if (-not ($metadata.compatible_hardware_revs -contains $TargetHardwareRev)) {
    throw "Bundled firmware must declare compatibility with hardware revision '$TargetHardwareRev'."
}
if ($metadata.firmware_version.Length -gt 32) {
    throw "Bundled firmware version exceeds controller metadata limit."
}
if ($metadata.build_label.Length -gt 48) {
    throw "Bundled build label exceeds controller metadata limit."
}

New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
Copy-Item -LiteralPath $sourceImagePath -Destination $assetPath -Force

$stampRoot = Split-Path -Parent (Split-Path -Parent $outputPath)
$stampPath = Join-Path $stampRoot "bundled-firmware-stamp.json"
$stampContent = [ordered]@{
    asset_file = $AssetFileName
    image_path = $sourceImagePath
    product_id = $metadata.product_id
    target_hardware_rev = $TargetHardwareRev
    hardware_rev = $metadata.hardware_rev
    firmware_version = $metadata.firmware_version
    build_label = $metadata.build_label
    runtime_protocol_version = $metadata.runtime_protocol_version
    image_kind = $metadata.image_kind
    compatible_hardware_revs = @($metadata.compatible_hardware_revs)
    image_size = $metadata.image_size
} | ConvertTo-Json -Depth 4
$stampContent | Set-Content -LiteralPath $stampPath -Encoding utf8

Write-BeetSuccess "Bundled firmware exported to $assetPath"
if ($outputMode -eq "reduced") {
    Write-BeetSuccess "Detailed logs: $(Get-BeetLogDirectory -Context $scriptContext)"
}
