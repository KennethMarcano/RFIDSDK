@echo off
setlocal EnableExtensions
cd /d "%~dp0"

call "%~dp0build.bat"
if errorlevel 1 (
    echo.
    echo Execucao cancelada — compilacao falhou.
    pause
    exit /b 1
)

set "CP=libs\lib_reader.jar;libs\lib_connect.jar;libs\jSerialComm-2.11.4.jar;SDKMERCURY\mercuryapi.jar;SDKMERCURY\ltkjava-1.0.0.6.jar;SDKMERCURY\slf4j-dependencies.jar;out"

echo Iniciando Perifericos eship (RFID + Balanca + Fluxo) ...
java --enable-native-access=ALL-UNNAMED -Djava.library.path="%CD%\SDKMERCURY" -cp "%CP%" com.peripheral.app.PeripheralApplication
set "ERR=%ERRORLEVEL%"
if not "%ERR%"=="0" pause
exit /b %ERR%
