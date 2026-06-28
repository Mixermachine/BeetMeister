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

function Get-FirmwareCacheKey {
    param([string]$RepoRoot)

    try {
        $gitHead = Get-GitValue -RepoRoot $RepoRoot -GitArgs @("rev-parse", "HEAD") -Fallback ""
        if (-not $gitHead) { return $null }

        $srcFiles = @(Get-ChildItem -Path (Join-Path $RepoRoot "firmware\esp-idf\components\beet_firmware\src\*.c") -File -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object { $_.FullName })
        $includeFiles = @(Get-ChildItem -Path (Join-Path $RepoRoot "firmware\esp-idf\components\beet_firmware\include\*.h") -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "beet_generated_metadata.h" } | Sort-Object Name | ForEach-Object { $_.FullName })

        $explicitFiles = @(
            (Join-Path $RepoRoot "firmware\esp-idf\main\beetmeister_main.c"),
            (Join-Path $RepoRoot "firmware\esp-idf\CMakeLists.txt"),
            (Join-Path $RepoRoot "firmware\esp-idf\components\beet_firmware\CMakeLists.txt"),
            (Join-Path $RepoRoot "firmware\esp-idf\main\CMakeLists.txt"),
            (Join-Path $RepoRoot "firmware\esp-idf\sdkconfig.defaults"),
            (Join-Path $RepoRoot "firmware\esp-idf\sdkconfig.defaults.esp32s3"),
            (Join-Path $RepoRoot "firmware\esp-idf\sdkconfig.release.defaults"),
            (Join-Path $RepoRoot "firmware\esp-idf\partitions\beetmeister.csv"),
            (Join-Path $RepoRoot "config\protocol_versions.properties"),
            (Join-Path $RepoRoot "firmware\esp-idf\components\beet_firmware\tools\gen_beet_metadata_header.py"),
            (Join-Path $RepoRoot "firmware\esp-idf\main\idf_component.yml")
        )

        $allFiles = $srcFiles + $includeFiles + $explicitFiles
        $hashEntries = [System.Collections.ArrayList]::new()
        [void]$hashEntries.Add("HEAD:$gitHead")

        foreach ($path in $allFiles) {
            if (-not (Test-Path -LiteralPath $path)) { return $null }
            $relativePath = $path.Substring($RepoRoot.Length + 1).Replace('\', '/')
            $fileHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            [void]$hashEntries.Add("$relativePath`:$fileHash")
        }

        $concatString = $hashEntries -join "|"
        $stream = [System.IO.MemoryStream]::new([System.Text.Encoding]::UTF8.GetBytes($concatString))
        $concatHash = (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash
        $stream.Dispose()
        return $concatHash
    } catch {
        return $null
    }
}

function Read-CachedFirmwareKey {
    param([string]$CacheKeyPath)

    try {
        if (-not (Test-Path -LiteralPath $CacheKeyPath)) { return $null }
        $json = Get-Content -LiteralPath $CacheKeyPath -Raw -Encoding UTF8 | ConvertFrom-Json
        return $json.cache_key
    } catch {
        return $null
    }
}

function Write-CachedFirmwareKey {
    param(
        [string]$CacheKeyPath,
        [string]$CacheKey,
        [string]$RepoRoot
    )

    $gitCommit = Get-GitValue -RepoRoot $RepoRoot -GitArgs @("rev-parse", "HEAD") -Fallback "unknown"
    $buildLabel = Get-GitValue -RepoRoot $RepoRoot -GitArgs @("describe", "--tags", "--always", "--dirty") -Fallback $gitCommit

    $cacheData = [ordered]@{
        cache_key = $CacheKey
        git_commit = $gitCommit
        build_label = $buildLabel
        created = (Get-Date -Format "o")
    }

    $parentDir = Split-Path -Parent $CacheKeyPath
    if (-not (Test-Path -LiteralPath $parentDir)) {
        New-Item -ItemType Directory -Force -Path $parentDir | Out-Null
    }
    $cacheData | ConvertTo-Json -Depth 2 | Set-Content -LiteralPath $CacheKeyPath -Encoding UTF8
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
    $cacheKeyPath = Join-Path $firmwareBuildPath ".beet-firmware-cache-key"
    $currentKey = Get-FirmwareCacheKey -RepoRoot $repoRoot
    $cachedKey = Read-CachedFirmwareKey -CacheKeyPath $cacheKeyPath
    $cachedBinary = Join-Path $firmwareBuildPath "beetmeister.bin"

    if ($currentKey -and $cachedKey -and ($currentKey -eq $cachedKey) -and (Test-Path -LiteralPath $cachedBinary) -and (-not $FullOutput)) {
        Write-BeetPhase "Using cached firmware build (no firmware inputs changed)."
        $sourceImagePath = $cachedBinary
    }
    else {
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
        $idfBuildArgsPacked = (@(
            "-B", $firmwareBuildPath,
            "-D", "SDKCONFIG=$releaseSdkconfigPath",
            "-D", "BEET_FIRMWARE_VERSION=$($stamp.FirmwareVersion)",
            "-D", "BEET_BUILD_LABEL=$($stamp.BuildLabel)",
            "build"
        ) -join [char]0x1f)
        $idfArgs = @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", $invokeIdf
        )
        if ($FullOutput) {
            $idfArgs += "-FullOutput"
        }
        $idfArgs += @(
            "-IdfArgsPacked", $idfBuildArgsPacked
        )
        Invoke-BeetProcess `
            -Context $scriptContext `
            -Description "build-firmware" `
            -FilePath "powershell.exe" `
            -ArgumentList $idfArgs `
            -WorkingDirectory (Join-Path $repoRoot "firmware\esp-idf") | Out-Null

        $sourceImagePath = Join-Path $firmwareBuildPath "beetmeister.bin"

        if ($currentKey) {
            Write-CachedFirmwareKey -CacheKeyPath $cacheKeyPath -CacheKey $currentKey -RepoRoot $repoRoot
        }
    }

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
