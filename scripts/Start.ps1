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
    docker compose exec -T api python -m app.seed
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
    Write-Host 'Эмулятор: http://10.0.2.2:8000. Аккаунты: anna, mikhail, olga, ivan. Пароль: Recipe2026!'
} finally { Pop-Location }
