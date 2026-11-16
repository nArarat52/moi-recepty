param([Parameter(Mandatory=$true)][string]$Backup,[switch]$ConfirmReplace)
$ErrorActionPreference='Stop'
if(-not $ConfirmReplace){throw 'Восстановление заменит текущую БД. После сохранения текущей копии повторите с -ConfirmReplace.'}
$backupPath=(Resolve-Path -LiteralPath $Backup).Path
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    docker compose stop api
    if($LASTEXITCODE -ne 0){throw 'Не удалось остановить API.'}
    docker compose cp $backupPath db:/tmp/recipe-restore.dump
    if($LASTEXITCODE -ne 0){throw 'Не удалось передать резервную копию.'}
    docker compose exec -T db pg_restore -U recipes -d recipes --clean --if-exists --no-owner --single-transaction /tmp/recipe-restore.dump
    if($LASTEXITCODE -ne 0){throw 'Восстановление отменено. БД сохранена в состоянии до транзакции.'}
    docker compose start api
    if($LASTEXITCODE -ne 0){throw 'Не удалось запустить API.'}
    Write-Host 'База и связанные фотографии восстановлены.'
} finally { Pop-Location }
