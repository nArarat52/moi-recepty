$ErrorActionPreference='Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try { docker compose stop } finally { Pop-Location }
