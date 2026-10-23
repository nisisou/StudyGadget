param([string]$Keystore)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
if(!$Keystore){$Keystore=Join-Path $project 'assets\.android-local\debug.keystore'}
if(!(Test-Path -LiteralPath $Keystore -PathType Leaf)){throw 'Original debug.keystore not found. Specify -Keystore explicitly.'}
$bytes=[IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $Keystore).Path)
if($bytes.Length -lt 100 -or $bytes.Length -gt 65536){throw 'Unexpected keystore size'}
Set-Clipboard -Value ([Convert]::ToBase64String($bytes))
Write-Host 'Copied the existing signing key as Base64 to the clipboard.'
Write-Host 'Paste it into GitHub repository Settings > Secrets and variables > Actions > New repository secret.'
Write-Host 'Secret name: ANDROID_SIGNING_KEYSTORE_BASE64'
Write-Host 'Do not paste the value into a commit, an issue, or this chat.'

