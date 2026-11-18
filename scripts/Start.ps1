param([switch]$UpdateDemoPassword)

$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    docker info --format '{{.ServerVersion}}' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Запустите Docker Desktop и дождитесь готовности.'}
    docker compose up -d --build
    if($LASTEXITCODE -ne 0){throw 'Не удалось запустить сервер.'}
    $ready=$false
    for($n=0;$n -lt 30;$n++){
        try { $status=Invoke-RestMethod http://127.0.0.1:8000/health -TimeoutSec 2; if($status.status -eq 'ok'){$ready=$true;break} } catch {}
        Start-Sleep -Seconds 1
    }
    if(-not $ready){throw 'Сервер не отвечает. Проверьте docker compose logs api.'}
    $passwordPath = Join-Path $projectRoot '.tmp\demo-account-password.txt'
    if (!(Test-Path -LiteralPath $passwordPath)) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $passwordPath) -Force | Out-Null
        $bytes = New-Object byte[] 24
        $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
        $temporaryPath = "$passwordPath.$PID"
        [IO.File]::WriteAllText($temporaryPath, [Convert]::ToBase64String($bytes))
        try {
            [IO.File]::Move($temporaryPath, $passwordPath)
        } catch [IO.IOException] {
            if (!(Test-Path -LiteralPath $passwordPath)) { throw }
        } finally {
            if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath }
        }
    }
    $seedInput = @([IO.File]::ReadAllText($passwordPath).TrimEnd([char]13, [char]10))
    $seedArgs = @('--password-stdin')
    if ($UpdateDemoPassword) {
        $currentPassword = Read-Host 'Введите действующий пароль демо-аккаунтов' -AsSecureString
        $seedInput += [PSCredential]::new('demo', $currentPassword).GetNetworkCredential().Password
        $seedArgs += '--update-demo-password'
    }
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    $seedInput | docker compose exec -T api python -m app.seed @seedArgs
    if($LASTEXITCODE -ne 0){throw 'Не удалось подготовить тестовые данные.'}
    $sdkRoot=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
    $adb=Join-Path $sdkRoot 'platform-tools\adb.exe'
    if(Test-Path -LiteralPath $adb){
        $devices=& $adb devices
        foreach($line in $devices){
            if($line -match '^([^\s]+)\s+device$'){
                $serial=$Matches[1]
                & $adb -s $serial reverse tcp:8000 tcp:8000 | Out-Null
                if($LASTEXITCODE -eq 0){Write-Host "USB/эмулятор $serial : http://127.0.0.1:8000"}
            }
        }
    }
    Write-Host 'Сервер готов: http://127.0.0.1:8000/docs'
    Write-Host 'Эмулятор: http://10.0.2.2:8000. Аккаунты: anna, mikhail, olga, ivan.'
    Write-Host "Пароль демо-аккаунтов сохранён в $passwordPath"
} finally { Pop-Location }
