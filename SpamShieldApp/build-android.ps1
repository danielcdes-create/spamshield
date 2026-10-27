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
    $metaPath = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\output-metadata.json'
    if (Test-Path $apkPath) {
        $distDir = Join-Path $PSScriptRoot 'dist'
        if (-not (Test-Path $distDir)) {
            New-Item -ItemType Directory -Path $distDir -Force | Out-Null
        }
        $targetApk = Join-Path $distDir 'SpamShieldBR-debug.apk'
        Copy-Item -Path $apkPath -Destination $targetApk -Force
        if (Test-Path $metaPath) {
            Copy-Item -Path $metaPath -Destination (Join-Path $distDir 'output-metadata.json') -Force
        }
        $hash = (Get-FileHash -Path $targetApk -Algorithm SHA256).Hash.ToLowerInvariant()
        $size = (Get-Item $targetApk).Length
        $info = @{
            version_code = 13
            version_name = "1.2.9"
            size_bytes = $size
            sha256_checksum = $hash
            download_path = "/apk"
        }
        if (Test-Path $metaPath) {
            try {
                $metaJson = Get-Content $metaPath -Raw | ConvertFrom-Json
                if ($metaJson.elements -and $metaJson.elements[0]) {
                    $info.version_code = [int]$metaJson.elements[0].versionCode
                    $info.version_name = [string]$metaJson.elements[0].versionName
                }
            } catch {}
        }
        $info | ConvertTo-Json -Depth 4 | Set-Content -Path (Join-Path $distDir 'apk-info.json') -Encoding utf8
        Write-Host "APK Debug gerado com sucesso em: $apkPath" -ForegroundColor Green
        Write-Host "Publicado em: $targetApk (SHA-256: $hash, Tamanho: $size bytes)" -ForegroundColor Cyan
    }
} finally {
    Pop-Location
}
