param([string[]]$Targets=@(':app:assembleDebug',':app:verifyUnitTestsInProcess'))
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$project=Join-Path $taskRoot 'assets'
$javaHome='C:\Program Files\Java\jdk-17'
$gradle='C:\Users\haibo\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13'
$taskCache='C:\Users\haibo\AppData\Local\Temp\study-companion-gradle'
$androidHome=Join-Path $project '.android-local'
New-Item -ItemType Directory -Force -Path $taskCache,$androidHome | Out-Null
if (!(Test-Path (Join-Path $androidHome 'debug.keystore')) -and (Test-Path 'C:\Users\haibo\.android\debug.keystore')) {
    Copy-Item -LiteralPath 'C:\Users\haibo\.android\debug.keystore' -Destination (Join-Path $androidHome 'debug.keystore')
}
$env:JAVA_HOME=$javaHome
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
$env:GRADLE_USER_HOME=$taskCache
$env:GRADLE_RO_DEP_CACHE='C:\Users\haibo\.gradle\caches'
$env:ANDROID_USER_HOME=$androidHome
Push-Location $project
try {
    & (Join-Path $javaHome 'bin\java.exe') '-Xmx2048m' '-Dfile.encoding=UTF-8' '-Dorg.gradle.internal.instrumentation.agent=false' -cp (Join-Path $gradle 'lib\gradle-gradle-cli-main-8.13.jar') org.gradle.launcher.GradleMain --offline --no-daemon '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Dorg.gradle.internal.instrumentation.agent=false' '-Pkotlin.compiler.execution.strategy=in-process' -I (Join-Path $PSScriptRoot 'android-local.init.gradle') @Targets --console=plain
    if($LASTEXITCODE -ne 0){throw 'Android build or verification failed'}
    $release=Join-Path $taskRoot 'release'
    New-Item -ItemType Directory -Force -Path $release | Out-Null
    if($Targets -contains ':app:assembleDebug'){Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $release 'StudyCompanion-character-debug.apk')}
} finally { Pop-Location }
