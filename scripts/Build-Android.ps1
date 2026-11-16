param([switch]$Install)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$javaCandidates=@(
    "$env:ProgramFiles\Android\Android Studio1\jbr",
    "$env:ProgramFiles\Android\Android Studio\jbr",
    $env:JAVA_HOME
)
$jdk=$javaCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'lib\jvm.cfg')) } | Select-Object -First 1
if(-not $jdk){throw 'Установите Android Studio с JDK 17+ или укажите JAVA_HOME.'}
$env:JAVA_HOME=$jdk
if(-not $env:ANDROID_HOME){$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
Push-Location (Join-Path $projectRoot 'android')
try {
    & .\gradlew.bat "-Dorg.gradle.java.home=$jdk" assembleDebug --console=plain
    if($LASTEXITCODE -ne 0){throw 'Сборка Android завершилась ошибкой.'}
    $apk=Join-Path $projectRoot 'releases\MoiRecepty-1.0.0-demo.apk'
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination $apk -Force
    $hash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $projectRoot 'releases\SHA256SUMS.txt'),"$hash  MoiRecepty-1.0.0-demo.apk`n",[System.Text.UTF8Encoding]::new($false))
    Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List
    if($Install){
        & (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') install -r $apk
        if($LASTEXITCODE -ne 0){throw 'Не удалось установить APK. Подключите Android-устройство или эмулятор.'}
        & (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') shell am start -n ru.recipe.app/.MainActivity
    }
} finally { Pop-Location }
