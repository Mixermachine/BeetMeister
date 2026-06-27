$port = New-Object System.IO.Ports.SerialPort COM6, 115200, None, 8, One
$port.ReadTimeout = 500
$port.NewLine = "`n"
$port.Open()
$port.DiscardInBuffer()

# Toggle RTS to reset
$port.RtsEnable = $true
Start-Sleep -Milliseconds 100
$port.RtsEnable = $false
Start-Sleep -Milliseconds 50
$port.RtsEnable = $true

$deadline = (Get-Date).AddSeconds(25)
while ((Get-Date) -lt $deadline) {
    try {
        $data = $port.ReadExisting()
        if ($data) {
            Write-Host -NoNewline $data
        }
    } catch {}
    Start-Sleep -Milliseconds 250
}
$port.Close()
