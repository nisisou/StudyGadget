$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$codeExe=Join-Path $env:LOCALAPPDATA 'Programs\Microsoft VS Code\Code.exe'
if(!(Test-Path -LiteralPath $codeExe)){
    $codeExe=Join-Path $env:ProgramFiles 'Microsoft VS Code\Code.exe'
}
if(!(Test-Path -LiteralPath $codeExe)){throw 'Install Visual Studio Code first.'}
# Development host loads this local extension without changing installed extensions.
$extension=Join-Path $PSScriptRoot 'vscode-extension'
$arguments=@('--new-window',('--extensionDevelopmentPath="'+$extension+'"'),('"'+$project+'"'))
Start-Process -FilePath $codeExe -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden

