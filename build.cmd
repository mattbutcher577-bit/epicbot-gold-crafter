@echo off
setlocal
cd /d "%~dp0"
title EpicBot Gold Crafter Builder
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
if errorlevel 1 (
  echo.
  echo BUILD FAILED
  pause
  exit /b 1
)
echo.
echo BUILD COMPLETE
pause
