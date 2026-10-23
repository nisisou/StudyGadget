param([string]$Python)
$ErrorActionPreference = 'Stop'
if(!$Python){$Python=$env:STUDYGADGET_PYTHON}
if(!$Python){
    $pythonTool=Get-Command python -ErrorAction SilentlyContinue
    if($pythonTool){$Python=$pythonTool.Source}
    else{
        $pythonBase=Join-Path $env:LOCALAPPDATA 'Programs\Python'
        $pythonCandidate=Get-ChildItem -LiteralPath $pythonBase -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object {Join-Path $_.FullName 'python.exe'} |
            Where-Object {Test-Path -LiteralPath $_} | Select-Object -First 1
        if($pythonCandidate){$Python=$pythonCandidate}else{throw 'Python 3 is required. Set STUDYGADGET_PYTHON.'}
    }
}

$taskRoot = Split-Path -Parent $PSScriptRoot
$devkit = 'C:\devkitPro'
$compiler = Join-Path $devkit 'devkitARM\bin\arm-none-eabi-gcc.exe'
& (Join-Path $PSScriptRoot 'prepare-home.ps1') -Python $Python
if (!$?) { throw 'HOME assets failed' }
Push-Location (Join-Path $taskRoot 'assets')
try {
    & (Join-Path $devkit 'tools\bin\picasso.exe') -o character.shbin character.v.pica
    if ($LASTEXITCODE -ne 0) { throw 'Shader compilation failed' }
    & $Python (Join-Path $PSScriptRoot 'embed_shader.py') character.shbin character_shader.c character_shader.h
    if ($LASTEXITCODE -ne 0) { throw 'Shader embedding failed' }
    $flags = @('-std=gnu11','-Wall','-Wextra','-O2','-mword-relocations','-ffunction-sections','-march=armv6k','-mtune=mpcore','-mfloat-abi=hard','-mtp=soft','-D__3DS__',"-I$devkit/libctru/include")
    & $compiler @flags main.c character.c character_sync.c network_client.c network_peer.c app_update.c update_sha256.c dev_relaunch.c character_shader.c '-specs=3dsx.specs' "-L$devkit/libctru/lib" -lcitro2d -lcitro3d -lctru -lm -o studygadget.elf
    if ($LASTEXITCODE -ne 0) { throw '3DS compilation failed' }
    & (Join-Path $devkit 'tools\bin\3dsxtool.exe') studygadget.elf studygadget.3dsx "--smdh=$taskRoot/home-menu/studygadget.smdh"
    if ($LASTEXITCODE -ne 0) { throw '3DS packaging failed' }
    Copy-Item -LiteralPath studygadget.3dsx -Destination (Join-Path $taskRoot 'studygadget.3dsx')
    Copy-Item -LiteralPath studygadget.elf -Destination (Join-Path $taskRoot 'studygadget.elf')
} finally { Pop-Location }
