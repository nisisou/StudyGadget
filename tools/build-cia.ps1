param(
    [string]$BannerCgfx,
    [string]$BannerTool,
    [string]$MakeRom,
    [switch]$CgfxValidated
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$homeAssets = Join-Path $taskRoot 'home-menu'
# CgfxValidated is retained for compatibility; actual byte inspection always runs.
if (!$BannerCgfx) { $BannerCgfx = Join-Path $homeAssets 'banner.cgfx' }
function Find-PackagingTool([string]$Explicit, [string]$Name) {
    if ($Explicit) {
        if (!(Test-Path -LiteralPath $Explicit -PathType Leaf)) { throw "Tool not found: $Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    $local = Join-Path $taskRoot "tools\homebrew\$Name.exe"
    if (Test-Path -LiteralPath $local) { return $local }
    $legacyLocal = Join-Path $taskRoot "tools\$Name.exe"
    if (Test-Path -LiteralPath $legacyLocal) { return $legacyLocal }
    $command = Get-Command "$Name.exe" -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "Missing $Name.exe. See home-menu/README.md. No CIA has been built."
}
# Check every prerequisite before producing a package. A glTF is not a CGFX.
$BannerTool = Find-PackagingTool $BannerTool 'bannertool'
$MakeRom = Find-PackagingTool $MakeRom 'makerom'
if (!(Test-Path -LiteralPath $BannerCgfx -PathType Leaf)) {
    throw 'Missing converted and validated banner.cgfx. See home-menu/README.md. No CIA has been built.'
}
$BannerCgfx = (Resolve-Path -LiteralPath $BannerCgfx).Path
$cgfxBytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $BannerCgfx).Path)
if ($cgfxBytes.Length -gt 524288 -or $cgfxBytes.Length -lt 32 -or [Text.Encoding]::ASCII.GetString($cgfxBytes,0,4) -ne 'CGFX') {
    throw 'The banner must be a CGFX file of at most 512 KiB.'
}
& (Join-Path $PSScriptRoot 'build-3ds.ps1')
if (!$?) { throw '3DS build failed' }
python (Join-Path $PSScriptRoot 'cgfx_inspect.py') $BannerCgfx --source (Join-Path $homeAssets 'banner.glb') --report (Join-Path $homeAssets 'cgfx-inspection.json')
if ($LASTEXITCODE -ne 0) { throw 'CGFX binary inspection failed; CIA build stopped' }
# Older native packagers can fail on Japanese paths. Stage only their inputs in
# an ASCII workspace; the Android Studio project is never moved.
$staging = 'C:\Users\haibo\AppData\Local\Temp\studygadget-cia-build'
New-Item -ItemType Directory -Force -Path $staging | Out-Null
Copy-Item -LiteralPath $BannerCgfx -Destination (Join-Path $staging 'banner.cgfx')
foreach ($name in @('icon.png','silence.wav','studygadget.rsf')) {
    Copy-Item -LiteralPath (Join-Path $homeAssets $name) -Destination (Join-Path $staging $name)
}
Copy-Item -LiteralPath (Join-Path $taskRoot 'assets\studygadget.elf') -Destination (Join-Path $staging 'studygadget.elf')
Push-Location $staging
try {
    & $BannerTool makesmdh -s 'Study Gadget' -l 'Character study timer' -p 'Study Gadget' -f 'visible,allow3d,recordusage,extendedbanner' -i icon.png -o icon.smdh
    if ($LASTEXITCODE -ne 0) { throw 'CIA SMDH creation failed' }
    & $BannerTool makebanner -ci banner.cgfx -a silence.wav -o banner.bnr
    if ($LASTEXITCODE -ne 0) { throw 'Banner packaging failed' }
    # app:4 is makerom's standard application descriptor, including SD/network services.
    & $MakeRom -f cia -target t -desc app:4 -exefslogo -o studygadget-new.cia -elf studygadget.elf -rsf studygadget.rsf -icon icon.smdh -banner banner.bnr -major 1 -minor 2 -micro 0
    if ($LASTEXITCODE -ne 0) { throw 'CIA packaging failed' }
    if (!(Test-Path -LiteralPath 'studygadget-new.cia')) { throw 'makerom did not produce a CIA' }
    New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot 'release\cia') | Out-Null
    Copy-Item -LiteralPath 'studygadget-new.cia' -Destination (Join-Path $taskRoot 'release\cia\studygadget.cia')
    python (Join-Path $PSScriptRoot 'verify_home_cia.py') --cia (Join-Path $taskRoot 'release\cia\studygadget.cia') --cgfx $BannerCgfx --source (Join-Path $homeAssets 'banner.glb')
    if ($LASTEXITCODE -ne 0) { throw 'CIA embedded banner verification failed' }
    Get-FileHash -LiteralPath (Join-Path $taskRoot 'release\cia\studygadget.cia') -Algorithm SHA256
} finally { Pop-Location }
