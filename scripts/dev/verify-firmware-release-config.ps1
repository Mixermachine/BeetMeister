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
        [string]$Expected,
        [string]$Alternate
    )

    if (($lines -contains $Expected) -or (-not [string]::IsNullOrWhiteSpace($Alternate) -and ($lines -contains $Alternate))) {
        return
    }

    if ($Alternate) {
        Write-Error "Missing required release setting: $Expected or $Alternate"
    } else {
        Write-Error "Missing required release setting: $Expected"
        exit 1
    }
}

Require-ExactLine "CONFIG_BEET_ENABLE_BENCH_DIAGNOSTICS=n" "# CONFIG_BEET_ENABLE_BENCH_DIAGNOSTICS is not set"

Write-Host "Release config checks passed for $SdkconfigPath"
