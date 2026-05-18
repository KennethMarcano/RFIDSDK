@echo off
cd /d "%~dp0"
set CP=libs\lib_reader.jar;libs\lib_connect.jar;libs\jSerialComm-2.9.1.jar;SDKMERCURY\mercuryapi.jar;SDKMERCURY\ltkjava-1.0.0.6.jar;SDKMERCURY\slf4j-dependencies.jar;out
java -Djava.library.path="%CD%\SDKMERCURY" -cp "%CP%" com.rfid.app.RfidApplication
