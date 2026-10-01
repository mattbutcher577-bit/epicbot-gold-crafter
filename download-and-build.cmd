@echo off
setlocal
title Download + Build EpicBot Gold Crafter
set "PS1=%TEMP%\epicbot-gold-crafter-download.ps1"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
  "Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/mattbutcher577-bit/epicbot-gold-crafter/main/download-and-build.ps1' -OutFile '%PS1%'"

if errorlevel 1 (
  echo.
  echo Could not download the build helper.
  pause
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS1%"

if errorlevel 1 (
  echo.
  echo DOWNLOAD OR BUILD FAILED
  pause
  exit /b 1
)

echo.
echo DONE
pause
