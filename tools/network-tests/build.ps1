$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$vcvars = Get-Item -Path 'C:/Program Files/Microsoft Visual Studio/*/*/VC/Auxiliary/Build/vcvars64.bat' -ErrorAction SilentlyContinue | Select-Object -First 1
if (!$vcvars) { throw 'MSVC C++ tools are required for the Windows test adapter.' }
$buildDir = Join-Path $taskRoot 'diagnostics/network-host'
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
$commands = @('@echo off', ('call "' + $vcvars.FullName + '" >nul'), 'cl /nologo /std:c11 /W3 /D_CRT_SECURE_NO_WARNINGS /Itools\network-tests\include /Iassets assets\network_client.c assets\update_sha256.c tools\network-tests\host.c tools\network-tests\runner.c /Fodiagnostics\network-host\ /Fediagnostics\network-host\network-client.exe /link ws2_32.lib')
$commandFile = Join-Path $buildDir 'build.cmd'
Set-Content -LiteralPath $commandFile -Value $commands -Encoding ascii
Push-Location $taskRoot
try {
    Add-Content -LiteralPath $commandFile -Value 'cl /nologo /std:c11 /W3 /D_CRT_SECURE_NO_WARNINGS /Iassets assets\app_update.c assets\update_sha256.c tools\network-tests\update_runner.c /Fodiagnostics\network-host\ /Fediagnostics\network-host\update-tools.exe' -Encoding ascii
    & cmd.exe /c $commandFile
    if ($LASTEXITCODE -ne 0) { throw 'Native updater tools build failed' }
} finally { Pop-Location }
