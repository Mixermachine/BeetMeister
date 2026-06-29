$repoRoot = (Get-Location).Path

function Get-GitValue {
    param([string]$RepoRoot, [string[]]$GitArgs, [string]$Fallback = "")
    try {
        $value = (& git -C $RepoRoot @GitArgs 2>$null)
        if ($LASTEXITCODE -eq 0) { return ($value | Select-Object -First 1).Trim() }
    } catch { }
    return $Fallback
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
            $relativePath = $path.Substring($RepoRoot.Length + 1) -replace '\', '/'
            $fileHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            [void]$hashEntries.Add("$relativePath`:$fileHash")
        }
        $concatString = $hashEntries -join "|"
        $stream = [System.IO.MemoryStream]::new([System.Text.Encoding]::UTF8.GetBytes($concatString))
        $concatHash = (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash
        $stream.Dispose()
        return $concatHash
    } catch { return $null }
}

Write-Host "Testing Get-FirmwareCacheKey..."

$key1 = Get-FirmwareCacheKey -RepoRoot $repoRoot
if ($key1) {
    Write-Host "  Key 1: $key1"
} else {
    Write-Host "  FAILED: key1 is null"
    exit 1
}

$key2 = Get-FirmwareCacheKey -RepoRoot $repoRoot
if ($key1 -eq $key2) {
    Write-Host "  Determinism: OK (same key on repeated call)"
} else {
    Write-Host "  FAILED: non-deterministic"
    Write-Host "  Key 2: $key2"
    exit 1
}

Write-Host "  Input files covered:"
$srcFiles = @(Get-ChildItem -Path (Join-Path $repoRoot "firmware\esp-idf\components\beet_firmware\src\*.c") -File -ErrorAction SilentlyContinue)
Write-Host "    .c source files: $($srcFiles.Count)"
$headerFiles = @(Get-ChildItem -Path (Join-Path $repoRoot "firmware\esp-idf\components\beet_firmware\include\*.h") -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "beet_generated_metadata.h" })
Write-Host "    .h header files: $($headerFiles.Count)"

# Touch a source file and verify key changes
$testFile = Join-Path $repoRoot "firmware\esp-idf\components\beet_firmware\src\beet_core.c"
$originalContent = Get-Content $testFile -Raw
Add-Content -Path $testFile -Value "`n/* cache-test-touch */"
$key3 = Get-FirmwareCacheKey -RepoRoot $repoRoot

# Restore immediately
Set-Content -Path $testFile -Value $originalContent -NoNewline

if ($key1 -ne $key3) {
    Write-Host "  Invalidation: OK (key changed after source file touched)"
} else {
    Write-Host "  FAILED: key unchanged after source file change"
    Write-Host "  Key 3: $key3"
    exit 1
}

Write-Host ""
Write-Host "All cache-key tests PASSED"
