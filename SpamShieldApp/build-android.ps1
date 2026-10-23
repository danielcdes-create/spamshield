$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Android\Android Studio\jbr' }
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
if ($env:JAVA_TOOL_OPTIONS -notmatch '--enable-native-access') {
    $env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS --enable-native-access=ALL-UNNAMED".Trim()
}

Push-Location $PSScriptRoot
try {
    & .\gradlew.bat testDebugUnitTest assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Falha no build Gradle (exit code $LASTEXITCODE)"
    }
    $apkPath = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (Test-Path $apkPath) {
        Write-Host "APK Debug gerado com sucesso em: $apkPath" -ForegroundColor Green
    }
} finally {
    Pop-Location
}
