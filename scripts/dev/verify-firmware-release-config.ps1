param(
    [string]$SdkconfigPath = ".\firmware\esp-idf\sdkconfig"
)

if (-not (Test-Path $SdkconfigPath)) {
    Write-Error "sdkconfig not found at '$SdkconfigPath'. Build firmware first."
    exit 1
}

$lines = Get-Content -LiteralPath $SdkconfigPath

function Require-ExactLine {
    param(
        [string]$Expected
    )

    if (-not ($lines -contains $Expected)) {
        Write-Error "Missing required release setting: $Expected"
        exit 1
    }
}

Require-ExactLine "CONFIG_BEET_ENABLE_BENCH_DIAGNOSTICS=n"

Write-Host "Release config checks passed for $SdkconfigPath"
