@echo off
cd /d "%~dp0"
if /I "%~1"=="--update-demo-password" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start.ps1" -UpdateDemoPassword
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start.ps1"
)
pause
