param([string]$Destination)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
if(-not $Destination){$Destination=Join-Path $projectRoot ('backups\recipes-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.dump')}
$Destination=[IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
Push-Location $projectRoot
try {
    docker compose exec -T db pg_dump -U recipes -d recipes -Fc -f /tmp/recipe-backup.dump
    if($LASTEXITCODE -ne 0){throw 'Не удалось создать резервную копию.'}
    docker compose cp db:/tmp/recipe-backup.dump $Destination
    if($LASTEXITCODE -ne 0){throw 'Не удалось скопировать резервную копию.'}
    Write-Host "Резервная копия БД и фотографий: $Destination"
    Get-FileHash -LiteralPath $Destination -Algorithm SHA256
} finally { Pop-Location }
