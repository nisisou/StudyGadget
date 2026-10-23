param([string]$Python='python')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
& $Python (Join-Path $PSScriptRoot 'export_home_banner.py')
if ($LASTEXITCODE -ne 0) { throw 'HOME asset export failed' }
Push-Location (Join-Path $taskRoot 'home-menu')
try {
    & 'C:\devkitPro\tools\bin\smdhtool.exe' --create 'Study Gadget' 'Character study timer' 'Study Gadget' icon.png studygadget.smdh icon-small.png
    if ($LASTEXITCODE -ne 0) { throw 'SMDH creation failed' }
} finally { Pop-Location }
