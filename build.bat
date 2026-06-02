@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "OUT=out"
set "SRC=src"
set "LIBS=libs\lib_reader.jar;libs\lib_connect.jar;libs\jSerialComm-2.11.4.jar"
set "MERCURY=SDKMERCURY\mercuryapi.jar;SDKMERCURY\ltkjava-1.0.0.6.jar;SDKMERCURY\slf4j-dependencies.jar"
set "CP=%LIBS%;%MERCURY%"

if not exist "%OUT%\resources" mkdir "%OUT%\resources"
copy /Y "%SRC%\resources\images.png" "%OUT%\resources\images.png" >nul 2>&1

powershell -NoProfile -Command ^
  "Get-ChildItem -LiteralPath '%CD%\%SRC%' -Recurse -Filter '*.java' | " ^
  "Where-Object { $_.FullName -notmatch 'payne[\\/]test[\\/]Test\.java$' } | " ^
  "ForEach-Object { '\"' + ($_.FullName -replace '\\','/') + '\"' } | Set-Content -LiteralPath '%CD%\sources.lst' -Encoding ASCII"

if not exist sources.lst (
    echo ERRO: nenhum arquivo .java encontrado em %SRC%
    exit /b 1
)

echo Compilando projeto em %OUT% ...
javac -encoding UTF-8 -d "%OUT%" -cp "%CP%" -sourcepath "%SRC%" @sources.lst
set "ERR=%ERRORLEVEL%"
del /f /q sources.lst 2>nul

if not "%ERR%"=="0" (
    echo.
    echo ERRO na compilacao. Corrija os erros acima e execute novamente.
    exit /b %ERR%
)

echo Compilacao concluida.
exit /b 0
