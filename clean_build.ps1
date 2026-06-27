$env:IDF_PATH = "C:\esp\v6.0\esp-idf"
$env:IDF_PYTHON_ENV_PATH = "C:\Espressif\tools\python\v6.0\venv"
$env:IDF_TOOLS_PATH = "C:\Espressif\tools"
$env:ESP_IDF_VERSION = "6.0"
$env:ESP_ROM_ELF_DIR = "C:\Espressif\tools\esp-rom-elfs\20241011"
$env:PATH = "C:\Espressif\tools\python\v6.0\venv\Scripts;C:\Espressif\tools\cmake\4.0.3\bin;C:\Espressif\tools\ninja\1.12.1;C:\Espressif\tools\xtensa-esp-elf\esp-15.2.0_20251204\xtensa-esp-elf\bin;C:\Espressif\tools\riscv32-esp-elf\esp-15.2.0_20251204\riscv32-esp-elf\bin;$env:PATH"
Remove-Item Env:MSYSTEM -ErrorAction SilentlyContinue

Set-Location C:\git\BeetMeister\firmware\esp-idf

Write-Host "=== Clean build ==="
$proc = Start-Process -NoNewWindow -Wait -PassThru -RedirectStandardOutput stdout.txt -RedirectStandardError stderr.txt -FilePath C:\Espressif\tools\python\v6.0\venv\Scripts\python.exe -ArgumentList @("C:\esp\v6.0\esp-idf\tools\idf.py", "build")
Write-Host "EXIT: $($proc.ExitCode)"
$stdout = Get-Content stdout.txt -Raw
if ($stdout) { 
    $lines = $stdout -split "`n"
    $start = [Math]::Max(0, $lines.Count - 15)
    $lines[$start..($lines.Count-1)] | Write-Host
}
$stderr = Get-Content stderr.txt -Raw
if ($stderr) { 
    $lines = $stderr -split "`n"
    $start = [Math]::Max(0, $lines.Count - 10)
    $lines[$start..($lines.Count-1)] | Write-Host
}
