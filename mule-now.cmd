@echo off
setlocal
title EpicBot Gold Crafter - MULE NOW

set "MULEDIR=%USERPROFILE%\.epicbot\gold-crafter\mule"
if not exist "%MULEDIR%" mkdir "%MULEDIR%"

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
  "$id=[guid]::NewGuid().ToString(); $now=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); $p=@('id='+$id,'phase=PREPARE','initiator=','roster=','round=0','reason=','finalSurvivor=','createdAt='+$now,'updatedAt='+$now); [IO.File]::WriteAllLines('%MULEDIR%\command.properties',$p); Write-Host ('MULE NOW broadcast: '+$id)"

echo.
echo All running Gold Crafter workers on this Windows account will:
echo   - finish/liquidate crafted jewellery
echo   - withdraw ALL banked GP
echo   - hop to world 698
echo   - meet behind Varrock sawmill at Wilderness level 5
echo   - relay the GP by controlled worker-vs-worker deaths
echo.
pause
