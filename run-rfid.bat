@echo off
cd /d "%~dp0"
if not exist out\resources mkdir out\resources
copy /Y src\resources\images.png out\resources\images.png >nul 2>&1
set CP=libs\lib_reader.jar;libs\lib_connect.jar;libs\jSerialComm-2.11.4.jar;SDKMERCURY\mercuryapi.jar;SDKMERCURY\ltkjava-1.0.0.6.jar;SDKMERCURY\slf4j-dependencies.jar;out
java --enable-native-access=ALL-UNNAMED -Djava.library.path="%CD%\SDKMERCURY" -cp "%CP%" com.peripheral.app.PeripheralApplication
