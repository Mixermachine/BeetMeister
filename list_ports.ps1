Get-WmiObject Win32_SerialPort | ForEach-Object {
    Write-Host "$($_.DeviceID) - $($_.Description)"
}
