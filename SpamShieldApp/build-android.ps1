param([ValidateSet('debug', 'release')][string]$BuildType = 'release')
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
    if ($BuildType -eq 'release') {
        foreach ($settingName in @('SPAMSHIELD_KEYSTORE_PATH', 'SPAMSHIELD_KEYSTORE_PASSWORD', 'SPAMSHIELD_KEY_ALIAS', 'SPAMSHIELD_KEY_PASSWORD')) {
            if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($settingName))) {
                throw "Configure $settingName para assinar o APK de distribuicao. Nenhuma chave nova sera criada automaticamente."
            }
        }
    }
    $assemblyTask = if ($BuildType -eq 'release') { 'assembleRelease' } else { 'assembleDebug' }
    & .\gradlew.bat testDebugUnitTest $assemblyTask --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Falha no build Gradle (exit code $LASTEXITCODE)"
    }
    $apkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\$BuildType\app-$BuildType.apk"
    $metaPath = Join-Path $PSScriptRoot "app\build\outputs\apk\$BuildType\output-metadata.json"
    if (Test-Path $apkPath) {
        $distDir = Join-Path $PSScriptRoot 'dist'
        if (-not (Test-Path $distDir)) {
            New-Item -ItemType Directory -Path $distDir -Force | Out-Null
        }
        $metaJson = Get-Content -LiteralPath $metaPath -Raw | ConvertFrom-Json
        $versionInfo = $metaJson.elements[0]
        $targetApk = Join-Path $distDir "SpamShieldBR-$($versionInfo.versionName)-$BuildType.apk"
        Copy-Item -Path $apkPath -Destination $targetApk -Force
        if (Test-Path $metaPath) {
            Copy-Item -Path $metaPath -Destination (Join-Path $distDir "output-metadata-$($versionInfo.versionName)-$BuildType.json") -Force
        }
        $hash = (Get-FileHash -Path $targetApk -Algorithm SHA256).Hash.ToLowerInvariant()
        $size = (Get-Item $targetApk).Length
        $info = @{
            version_code = [int]$versionInfo.versionCode
            version_name = [string]$versionInfo.versionName
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
        # A build prepares a candidate; it must not advance metadata for an older served APK.
        $infoPath = Join-Path $distDir "apk-info-$($versionInfo.versionName)-$BuildType.json"
        [System.IO.File]::WriteAllText($infoPath, ($info | ConvertTo-Json -Depth 4), [System.Text.UTF8Encoding]::new($false))
        Write-Host "APK $BuildType gerado com sucesso em: $apkPath" -ForegroundColor Green
        Write-Host "Preparado localmente: $targetApk (SHA-256: $hash, Tamanho: $size bytes)" -ForegroundColor Cyan
    }
} finally {
    Pop-Location
}
